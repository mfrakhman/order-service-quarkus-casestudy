package org.acme.order.entity;

// mirrors ecom's enum minus CART (no cart flow) and payment statuses (descoped)
public enum OrderStatus {
    PENDING,
    COMPLETED,
    CANCELLED
}
