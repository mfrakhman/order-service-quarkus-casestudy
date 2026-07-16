package org.acme.order.service;

import java.time.Instant;
import java.util.UUID;

import org.acme.order.client.ProductServiceClient;
import org.acme.order.dto.CreateOrderRequest;
import org.acme.order.dto.ReserveStockRequest;
import org.acme.order.entity.Order;
import org.acme.order.entity.OrderItem;
import org.acme.order.entity.OrderStatus;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class OrderService {

    @RestClient
    ProductServiceClient productClient;

    /**
     * Sync baseline (P1a): block on the product-service reservation, then
     * write the order. Intake is deliberately coupled to product-service
     * latency and the DB write — this is the design the k6 baseline measures.
     */
    public Order submit(CreateOrderRequest request) {
        try (Response response = productClient.reserve(new ReserveStockRequest(request.items()))) {
            int status = response.getStatus();
            if (status == 409) {
                throw new InsufficientStockException();
            }
            if (status != 200) {
                throw new IllegalStateException("product-service reserve failed: HTTP " + status);
            }
        }
        return persistCompleted(request);
    }

    @Transactional
    Order persistCompleted(CreateOrderRequest request) {
        Instant now = Instant.now();
        Order order = new Order();
        order.id = UUID.randomUUID();
        order.status = OrderStatus.COMPLETED;
        order.createdAt = now;
        order.completedAt = now;

        for (CreateOrderRequest.Item it : request.items()) {
            OrderItem item = new OrderItem();
            item.skuId = it.skuId();
            item.quantity = it.quantity();
            item.order = order;
            order.items.add(item);
        }
        order.persist();
        return order;
    }

    public Order findWithItems(UUID id) {
        return Order
                .<Order>find("from Order o left join fetch o.items where o.id = ?1", id)
                .firstResult();
    }
}
