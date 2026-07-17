package org.acme.order.dto;

import java.util.UUID;

import org.acme.order.entity.OrderStatus;

public record OrderAcceptedResponse(UUID id, OrderStatus status) {
}
