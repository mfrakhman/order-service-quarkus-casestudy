package org.acme.order.exception;

// Genuinely no such product — 404, no order created (ARCHITECTURE.md §7.3).
public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String productId) {
        super("product not found: " + productId);
    }
}
