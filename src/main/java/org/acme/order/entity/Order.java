package org.acme.order.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order extends PanacheEntityBase {

    @Id
    public UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    // set when the order reaches COMPLETED/CANCELLED — drives the
    // end-to-end completion metric (order.created -> terminal state)
    @Column(name = "completed_at")
    public Instant completedAt;
}
