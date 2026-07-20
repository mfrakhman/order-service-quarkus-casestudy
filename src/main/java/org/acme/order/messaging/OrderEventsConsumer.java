package org.acme.order.messaging;

import java.time.Instant;
import java.util.UUID;

import org.acme.order.entity.Order;
import org.acme.order.entity.OrderStatus;
import org.acme.order.event.OrderCreatedEvent;
import org.acme.order.event.StockReplyEvent;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * v3's three consumers (ARCHITECTURE.md §7.3/§7.5) — deliberately simple
 * compared to v2's: the order row is inserted synchronously *before*
 * order.created is even published, so the fan-out race that forced v2's
 * retry/defer/republish machinery (a reply arriving before the persisted
 * row existed) is structurally impossible here. Every consumer below only
 * ever needs a single attempt. Any exception thrown here hits
 * failure-strategy=reject -> nack(requeue=false) -> ordering.dead-letter,
 * deliberately with no connectivity-vs-poison classification (§7.5).
 *
 * @Incoming("some-name") is Quarkus's version of a message listener — same
 * job as the `consume()` callback in the Nest reference project's
 * RabbitmqService, or @RabbitListener in Spring. Like @Channel on the
 * publishing side, the name here is just a label; the REAL queue name and
 * routing key it's bound to live in application.properties under
 * mp.messaging.incoming.<name>.* — that's also where the DLQ (dead-letter
 * queue) wiring and retry policy are configured, not in this file.
 */
@ApplicationScoped
public class OrderEventsConsumer {

    private static final Logger LOG = Logger.getLogger(OrderEventsConsumer.class);

    /**
     * order.created -> log only (spec-literal "listen and log"); no DB write.
     * JsonObject is Vert.x's generic JSON type (Quarkus is built on Vert.x
     * under the hood) — .mapTo(SomeType.class) converts it into our typed
     * record, the same job JSON.parse(...) does in the Nest reference project.
     */
    @Incoming("order-created-in")
    @RunOnVirtualThread
    public void logOrderCreated(JsonObject json) {
        OrderCreatedEvent event = json.mapTo(OrderCreatedEvent.class);
        LOG.infof("order.created: orderId=%s productId=%s quantity=%d",
                event.orderId(), event.productId(), event.quantity());
    }

    /** stock.reserved -> PENDING -> COMPLETED. */
    @Incoming("stock-reserved")
    @RunOnVirtualThread
    public void onStockReserved(JsonObject json) {
        applyTransition(json.mapTo(StockReplyEvent.class), OrderStatus.COMPLETED);
    }

    /** stock.rejected -> PENDING -> CANCELLED (sad-path completion, not compensation). */
    @Incoming("stock-rejected")
    @RunOnVirtualThread
    public void onStockRejected(JsonObject json) {
        StockReplyEvent event = json.mapTo(StockReplyEvent.class);
        LOG.debugf("order %s rejected: %s", event.orderId(), event.reason());
        applyTransition(event, OrderStatus.CANCELLED);
    }

    /**
     * Guarded update — only a PENDING row transitions. A redelivered reply
     * finds a non-PENDING row and no-ops: naturally idempotent, no retry loop
     * needed since the row is guaranteed to already exist (see class javadoc).
     */
    private void applyTransition(StockReplyEvent event, OrderStatus target) {
        UUID id = UUID.fromString(event.orderId());
        QuarkusTransaction.requiringNew().run(() -> {
            Order.update("status = ?1, completedAt = ?2 where id = ?3 and status = ?4",
                    target, Instant.now(), id, OrderStatus.PENDING);
        });
    }
}
