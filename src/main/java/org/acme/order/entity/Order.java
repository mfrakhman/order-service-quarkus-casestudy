package org.acme.order.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// v3 (ARCHITECTURE.md §7.2): spec fields id, productId, totalPrice, status,
// createdAt — single product per order, no order_items/cart. quantity isn't
// persisted (spec doesn't list it); it only travels in the request and the
// order.created payload.
@Entity
@Table(name = "orders")
public class Order extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "product_id", nullable = false)
    public String productId;

    @Column(name = "total_price", nullable = false)
    public BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public OrderStatus status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    // set on the PENDING -> COMPLETED|CANCELLED transition (stock.reserved /
    // stock.rejected reply) — not a spec field, kept because it feeds the
    // completion-latency metric the k6 chapter measures (ARCHITECTURE.md §7.2)
    @Column(name = "completed_at")
    public Instant completedAt;
}
