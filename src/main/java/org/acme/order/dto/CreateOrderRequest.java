package org.acme.order.dto;

import java.util.List;

public record CreateOrderRequest(List<Item> items) {

    public record Item(String skuId, int quantity) {
    }

    public String validationError() {
        if (items == null || items.isEmpty()) {
            return "items must not be empty";
        }
        for (Item item : items) {
            if (item.skuId() == null || item.skuId().isBlank()) {
                return "items[].skuId is required";
            }
            if (item.quantity() < 1) {
                return "items[].quantity must be >= 1";
            }
        }
        return null;
    }
}
