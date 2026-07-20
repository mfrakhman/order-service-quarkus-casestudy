package org.acme.order.event;

/**
 * Payload of `stock.reserved` / `stock.rejected` (reason set on rejection
 * only). No deferrals counter in v3 (ARCHITECTURE.md §7.3) — the row this
 * reply concerns is guaranteed to already exist, so there's nothing to defer.
 */
public record StockReplyEvent(String orderId, String reason) {
}
