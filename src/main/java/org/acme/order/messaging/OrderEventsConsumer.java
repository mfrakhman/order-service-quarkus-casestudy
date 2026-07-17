package org.acme.order.messaging;

import java.time.Instant;
import java.util.UUID;

import org.acme.order.entity.Order;
import org.acme.order.entity.OrderItem;
import org.acme.order.entity.OrderStatus;
import org.acme.order.event.OrderCreatedEvent;
import org.acme.order.event.StockReplyEvent;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The three embedded consumers (ARCHITECTURE.md §4). Any exception thrown here
 * hits failure-strategy=reject → nack(requeue=false) → ordering.dead-letter,
 * so an undesigned code path is audit-visible instead of silently dropped.
 */
@ApplicationScoped
public class OrderEventsConsumer {

    private static final Logger LOG = Logger.getLogger(OrderEventsConsumer.class);

    // Fan-out race (§5): a stock.* reply can beat the persist INSERT — at
    // saturation by *minutes* (run 7: persist backlog peaked ~28k messages).
    // So: a few quick in-place retries for the common near-miss, then defer by
    // republishing the reply to the back of its own queue instead of blocking
    // a consumer slot. deferrals caps the loop: a reply whose order never
    // appears (its persist message dead-lettered) eventually goes to the DLQ.
    private static final int IN_PLACE_RETRIES = 3;
    private static final long RETRY_SLEEP_MS = 100;
    private static final int MAX_DEFERRALS = 100;

    @Channel("stock-reserved-out")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 256)
    MutinyEmitter<StockReplyEvent> reservedDeferrals;

    @Channel("stock-rejected-out")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 256)
    MutinyEmitter<StockReplyEvent> rejectedDeferrals;

    /** order.created → INSERT orders + order_items as PENDING (idempotent). */
    @Incoming("order-created-in")
    @RunOnVirtualThread
    public void persist(JsonObject json) {
        OrderCreatedEvent event = json.mapTo(OrderCreatedEvent.class);
        UUID id = UUID.fromString(event.orderId());
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                if (Order.findById(id) != null) {
                    return; // redelivery — row already committed
                }
                Order order = new Order();
                order.id = id;
                order.status = OrderStatus.PENDING;
                order.createdAt = Instant.parse(event.createdAt());
                for (OrderCreatedEvent.Item it : event.items()) {
                    OrderItem item = new OrderItem();
                    item.skuId = it.skuId();
                    item.quantity = it.quantity();
                    item.order = order;
                    order.items.add(item);
                }
                order.persist();
            });
        } catch (RuntimeException e) {
            if (isDuplicateKey(e)) {
                return; // concurrent redelivery won the insert race — same outcome
            }
            throw e;
        }
    }

    /** stock.reserved → PENDING → COMPLETED. */
    @Incoming("stock-reserved")
    @RunOnVirtualThread
    public void onStockReserved(JsonObject json) {
        applyTransition(json.mapTo(StockReplyEvent.class), OrderStatus.COMPLETED);
    }

    /** stock.rejected → PENDING → CANCELLED (sad-path completion, not compensation). */
    @Incoming("stock-rejected")
    @RunOnVirtualThread
    public void onStockRejected(JsonObject json) {
        StockReplyEvent event = json.mapTo(StockReplyEvent.class);
        LOG.debugf("order %s rejected: %s", event.orderId(), event.reason());
        applyTransition(event, OrderStatus.CANCELLED);
    }

    private void applyTransition(StockReplyEvent event, OrderStatus target) {
        UUID id = UUID.fromString(event.orderId());
        for (int attempt = 0; attempt < IN_PLACE_RETRIES; attempt++) {
            switch (tryTransition(id, target)) {
                case APPLIED, ALREADY_TERMINAL -> {
                    return; // ALREADY_TERMINAL = duplicate reply; guard makes it a no-op
                }
                case MISSING -> sleep(RETRY_SLEEP_MS);
            }
        }
        defer(event, target);
    }

    /** Row still missing — send the reply to the back of its own queue and ack. */
    private void defer(StockReplyEvent event, OrderStatus target) {
        int deferrals = event.deferralsOrZero();
        if (deferrals >= MAX_DEFERRALS) {
            // order never appeared (its persist message is in the DLQ?) — join it
            throw new IllegalStateException("order " + event.orderId() + " still absent after "
                    + deferrals + " deferrals; cannot apply " + target);
        }
        StockReplyEvent next = new StockReplyEvent(event.orderId(), event.reason(), deferrals + 1);
        MutinyEmitter<StockReplyEvent> out =
                target == OrderStatus.COMPLETED ? reservedDeferrals : rejectedDeferrals;
        out.sendAndAwait(next); // broker-confirmed before the original is acked
    }

    private TransitionResult tryTransition(UUID id, OrderStatus target) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // guarded update: only PENDING rows transition, terminal states never overwritten
            int updated = Order.update(
                    "status = ?1, completedAt = ?2 where id = ?3 and status = ?4",
                    target, Instant.now(), id, OrderStatus.PENDING);
            if (updated == 1) {
                return TransitionResult.APPLIED;
            }
            Order row = Order.findById(id);
            if (row == null) {
                return TransitionResult.MISSING;
            }
            // READ COMMITTED race (run 7, 1,060 swallowed replies): the persist
            // INSERT can commit between the UPDATE and this find — the row then
            // exists but is still PENDING. That is a near-miss, not a duplicate.
            return row.status == OrderStatus.PENDING
                    ? TransitionResult.MISSING
                    : TransitionResult.ALREADY_TERMINAL;
        });
    }

    private enum TransitionResult {
        APPLIED,
        ALREADY_TERMINAL,
        MISSING
    }

    private static boolean isDuplicateKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for persist consumer", e);
        }
    }
}
