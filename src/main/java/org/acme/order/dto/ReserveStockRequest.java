package org.acme.order.dto;

import java.util.List;

public record ReserveStockRequest(List<CreateOrderRequest.Item> items) {
}
