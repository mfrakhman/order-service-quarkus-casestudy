package org.acme.order.dto;

// v3 (ARCHITECTURE.md §7.3): single product + quantity, no cart.
public record CreateOrderRequest(String productId, Integer quantity) {

    public String validationError() {
        if (productId == null || productId.isBlank()) {
            return "productId is required";
        }
        if (quantity == null || quantity < 1) {
            return "quantity must be >= 1";
        }
        return null;
    }
}
