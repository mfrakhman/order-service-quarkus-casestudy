package org.acme.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.acme.order.entity.Order;
import org.acme.order.entity.OrderStatus;

// v3 (ARCHITECTURE.md §7.1): matches the spec's orders shape exactly.
public record OrderAcceptedResponse(
        UUID id, String productId, BigDecimal totalPrice, OrderStatus status, Instant createdAt) {

    public static OrderAcceptedResponse from(Order order) {
        return new OrderAcceptedResponse(
                order.id, order.productId, order.totalPrice, order.status, order.createdAt);
    }
}
