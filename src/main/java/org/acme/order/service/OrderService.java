package org.acme.order.service;

import java.time.Instant;
import java.util.UUID;

import org.acme.order.dto.CreateOrderRequest;
import org.acme.order.entity.Order;
import org.acme.order.event.OrderCreatedEvent;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;

import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OrderService {

    // Buffer sized above k6's maxVUs (500): every buffered event belongs to an
    // intake thread still blocked in sendAndAwait, so nothing here is a hidden
    // in-memory queue — no 201 leaves before the broker confirms.
    @Channel("order-created")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 2048)
    MutinyEmitter<OrderCreatedEvent> orderCreated;

    /**
     * Async intake (v2): validate → publish order.created → await the broker's
     * publish confirm → 201 {id, PENDING}. Intake never touches Postgres; the
     * broker is the durable buffer (ARCHITECTURE.md §3.1).
     */
    public UUID submit(CreateOrderRequest request) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        // virtual thread blocks until the message is acknowledged by RabbitMQ
        orderCreated.sendAndAwait(OrderCreatedEvent.of(id, request, createdAt));
        return id;
    }

    public Order findWithItems(UUID id) {
        return Order
                .<Order>find("from Order o left join fetch o.items where o.id = ?1", id)
                .firstResult();
    }
}
