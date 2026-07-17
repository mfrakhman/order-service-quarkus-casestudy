package org.acme.order.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.acme.order.dto.CreateOrderRequest;

/**
 * Published on rk `order.created`, consumed by both the persist queue and
 * product-service. String fields keep the JSON portable across Jackson
 * (Quarkus) and JSON.parse (Nest) without date-mapper coupling.
 */
public record OrderCreatedEvent(String orderId, List<Item> items, String createdAt) {

    public record Item(String skuId, int quantity) {
    }

    public static OrderCreatedEvent of(UUID orderId, CreateOrderRequest request, Instant createdAt) {
        List<Item> items = request.items().stream()
                .map(it -> new Item(it.skuId(), it.quantity()))
                .toList();
        return new OrderCreatedEvent(orderId.toString(), items, createdAt.toString());
    }
}
