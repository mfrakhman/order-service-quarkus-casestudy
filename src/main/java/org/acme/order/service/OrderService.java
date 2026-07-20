package org.acme.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.acme.order.cache.CacheService;
import org.acme.order.client.ProductClient;
import org.acme.order.client.ProductProfile;
import org.acme.order.dto.CreateOrderRequest;
import org.acme.order.dto.OrderAcceptedResponse;
import org.acme.order.entity.Order;
import org.acme.order.entity.OrderStatus;
import org.acme.order.event.OrderCreatedEvent;
import org.acme.order.exception.ProductNotFoundException;
import org.acme.order.exception.ProductServiceUnavailableException;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

@ApplicationScoped
public class OrderService {

    // ARCHITECTURE.md §7.4 placement A — existence+price gate, long TTL, no
    // invalidation (price/name immutable in v3, no update endpoint).
    private static final int PRODUCT_TTL_SECONDS = 60;
    // §7.4 placement B — order list, short TTL, no invalidation-on-write
    // (staleness-tolerant on purpose: a hot product's every order would
    // otherwise bust its own cache under the 1000rps workload's small SKU set).
    private static final int ORDER_LIST_TTL_SECONDS = 3;

    // MutinyEmitter is Quarkus's way of publishing a message to RabbitMQ — think
    // of it as the equivalent of RabbitmqService.publish() in the Nest reference
    // project, just injected instead of called on a service. @Channel("order-created")
    // is just a name — the ACTUAL exchange/routing-key it publishes to is configured
    // in application.properties under mp.messaging.outgoing.order-created.*. This
    // indirection (name here, config elsewhere) is a Quarkus/MicroProfile pattern:
    // the code says "I publish to a channel called order-created" and the config
    // says "and that channel means RabbitMQ, exchange X, routing key Y".
    private final MutinyEmitter<OrderCreatedEvent> orderCreated;
    private final CacheService cache;
    private final ObjectMapper objectMapper;
    private final ProductClient productClient;

    @Inject
    public OrderService(
            // Buffer sized above k6's maxVUs (500): every buffered event belongs to
            // an intake thread still blocked in sendAndAwait, so nothing here is a
            // hidden in-memory queue — no 201 leaves before the broker confirms.
            @Channel("order-created") @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 2048)
            MutinyEmitter<OrderCreatedEvent> orderCreated,
            CacheService cache,
            ObjectMapper objectMapper,
            // @RestClient tells Quarkus "give me a working implementation of the
            // ProductClient interface" — we never write the actual HTTP call, Quarkus
            // generates it from ProductClient.java's method + annotations. The base
            // URL lives in application.properties (quarkus.rest-client.product-service.url).
            @RestClient ProductClient productClient) {
        this.orderCreated = orderCreated;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.productClient = productClient;
    }

    // NOT the same key product-service uses for its own GET /products/:id
    // display cache — that one stores the full product (name, qty, createdAt,
    // ...) because it needs all of it for display. order-service only ever
    // needs the price for the existence+price gate (existence = "did we get
    // a value back at all"), so it gets its own key with just that — no JSON,
    // just the price as plain text. Sharing one key between two services that
    // want different shapes of the same product would mean whichever one
    // wrote last decides what the other one reads — a real bug waiting to
    // happen, not just extra complexity.
    private static String productPriceKey(String productId) {
        return "product-price:" + productId;
    }

    private static String orderListKey(String productId) {
        return "orders:by-product:" + productId;
    }

    /**
     * v3 intake (ARCHITECTURE.md §7.3): Redis-cached existence+price check →
     * INSERT orders row (source of truth, written before publish) → publish
     * order.created, await broker confirm → 201. Unlike v2, intake DOES touch
     * Postgres synchronously again — but only a single fast local INSERT, not
     * the blocking cross-service REST call that was v1's actual bottleneck.
     */
    public Order submit(CreateOrderRequest request) {
        BigDecimal price = resolveProductPrice(request.productId());
        if (price == null) {
            throw new ProductNotFoundException(request.productId());
        }

        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        int quantity = request.quantity();
        BigDecimal totalPrice = price.multiply(BigDecimal.valueOf(quantity));

        // QuarkusTransaction.requiringNew() is Quarkus's programmatic equivalent of
        // Spring's @Transactional — same idea (wrap this DB work in one transaction,
        // commit at the end, roll back on exception), just called as a method instead
        // of an annotation on the whole method. requiringNew() specifically means
        // "always start a brand-new transaction here, don't join one that might
        // already be open." .call(...) runs the lambda inside it and gives back
        // whatever the lambda returns (there's also .run(...) for lambdas with no
        // return value — used in OrderEventsConsumer.java for the status updates).
        Order order = QuarkusTransaction.requiringNew().call(() -> {
            Order o = new Order();
            o.id = id;
            o.productId = request.productId();
            o.totalPrice = totalPrice;
            o.status = OrderStatus.PENDING;
            o.createdAt = createdAt;
            o.persist();
            return o;
        });

        // sendAndAwait blocks this thread until RabbitMQ confirms the message was
        // received — same idea as `await rabbitmqService.publish(...)` in the Nest
        // reference project. "virtual thread" (see @RunOnVirtualThread in
        // OrderResource.java) is what makes blocking here cheap instead of tying up
        // a limited OS thread while we wait.
        orderCreated.sendAndAwait(OrderCreatedEvent.of(id, request.productId(), quantity, totalPrice, createdAt));
        return order;
    }

    public Order findById(UUID id) {
        return Order.findById(id);
    }

    /**
     * GET /orders/product/:productId (ARCHITECTURE.md §7.3/§7.4 placement B) —
     * read-through, short TTL, no invalidation-on-write.
     */
    public List<OrderAcceptedResponse> findByProductId(String productId) {
        String cachedJson = cache.get(orderListKey(productId));
        if (cachedJson != null) {
            try {
                return objectMapper.readValue(cachedJson, new TypeReference<List<OrderAcceptedResponse>>() {
                });
            } catch (Exception e) {
                // couldn't parse what was in the cache — treat it as a miss too
            }
        }
        List<OrderAcceptedResponse> fromDb = Order.<Order>list("productId", productId)
                .stream()
                .map(OrderAcceptedResponse::from)
                .toList();
        try {
            cache.set(orderListKey(productId), objectMapper.writeValueAsString(fromDb), ORDER_LIST_TTL_SECONDS);
        } catch (Exception e) {
            // couldn't serialize — skip caching this time, not fatal
        }
        return fromDb;
    }

    /**
     * Fail-open (§7.4): a cache error is a miss, never a request failure.
     * Only ever needs the price — existence is just "did we get a price back
     * at all" (from cache or from product-service), nothing else about the
     * product matters here. Package-private so the unit test can exercise
     * the cache/fallback branching directly, without needing a transaction
     * manager (submit()'s QuarkusTransaction.requiringNew() call needs a
     * live Quarkus context).
     */
    BigDecimal resolveProductPrice(String productId) {
        String cachedPrice = cache.get(productPriceKey(productId));
        if (cachedPrice != null) {
            try {
                return new BigDecimal(cachedPrice);
            } catch (NumberFormatException e) {
                // whatever was cached isn't a valid number — treat it as a miss too
            }
        }
        ProductProfile fetched = fetchFromProductService(productId);
        if (fetched == null) {
            return null; // genuinely no such product
        }
        cache.set(productPriceKey(productId), fetched.price().toString(), PRODUCT_TTL_SECONDS);
        return fetched.price();
    }

    private ProductProfile fetchFromProductService(String productId) {
        try {
            return productClient.getById(productId);
        } catch (WebApplicationException e) {
            // check the status directly rather than catching NotFoundException —
            // Quarkus's REST client throws its own ClientWebApplicationException
            // on 4xx, which does NOT extend jakarta.ws.rs.NotFoundException (the
            // same gotcha v1's REST client hit — ARCHITECTURE.md k6/results/v1)
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) {
                return null; // genuinely no such product
            }
            throw new ProductServiceUnavailableException(e);
        } catch (ProcessingException e) {
            throw new ProductServiceUnavailableException(e);
        }
    }
}
