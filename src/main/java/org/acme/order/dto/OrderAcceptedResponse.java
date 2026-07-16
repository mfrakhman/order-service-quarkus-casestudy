package org.acme.order.dto;

import java.util.UUID;

import org.acme.order.entity.Order;
import org.acme.order.entity.OrderStatus;

public record OrderAcceptedResponse(UUID id, OrderStatus status) {

    public static OrderAcceptedResponse of(Order order) {
        return new OrderAcceptedResponse(order.id, order.status);
    }
}
