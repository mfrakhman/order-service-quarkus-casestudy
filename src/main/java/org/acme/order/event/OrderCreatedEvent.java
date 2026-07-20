package org.acme.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published on rk `order.created` (ARCHITECTURE.md §7.3/§7.5) — fans out to
 * order-service's own log consumer and product-service's decrement consumer.
 * String/number fields keep the JSON portable across Jackson (Quarkus) and
 * JSON.parse (Nest) without date-mapper coupling.
 */
public record OrderCreatedEvent(
        String orderId, String productId, int quantity, BigDecimal totalPrice, String createdAt) {

    public static OrderCreatedEvent of(UUID orderId, String productId, int quantity,
            BigDecimal totalPrice, Instant createdAt) {
        return new OrderCreatedEvent(orderId.toString(), productId, quantity, totalPrice, createdAt.toString());
    }
}
