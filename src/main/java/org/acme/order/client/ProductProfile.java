package org.acme.order.client;

import java.math.BigDecimal;

/**
 * product-service's product shape — used both for the REST client response
 * and the Redis cache entry (ARCHITECTURE.md §7.4: one cache entry per
 * product, shared by the existence+price gate and any future display use).
 */
public record ProductProfile(String id, String name, BigDecimal price, int qty, String createdAt) {
}
