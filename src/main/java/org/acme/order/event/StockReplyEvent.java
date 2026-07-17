package org.acme.order.event;

/**
 * Payload of `stock.reserved` / `stock.rejected` (reason set on rejection only).
 * deferrals counts self-republishes by the status consumer while waiting for
 * the persist consumer to catch up (fan-out race) — null from product-service.
 */
public record StockReplyEvent(String orderId, String reason, Integer deferrals) {

    public int deferralsOrZero() {
        return deferrals == null ? 0 : deferrals;
    }
}
