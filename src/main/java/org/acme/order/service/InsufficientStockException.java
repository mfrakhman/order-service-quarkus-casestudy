package org.acme.order.service;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException() {
        super("insufficient stock");
    }
}
