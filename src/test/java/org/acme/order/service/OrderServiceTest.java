package org.acme.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.acme.order.cache.CacheService;
import org.acme.order.client.ProductClient;
import org.acme.order.client.ProductProfile;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.NotFoundException;

/**
 * Unit tests for the fail-open cache/fallback resolution logic
 * (ARCHITECTURE.md §7.3/§7.4) — the trickiest correctness path in v3's
 * intake and the one most worth pinning down with mocks. submit() itself
 * needs a live Quarkus transaction manager (QuarkusTransaction.requiringNew)
 * and isn't exercised here.
 */
class OrderServiceTest {

    private final CacheService cache = mock(CacheService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProductClient productClient = mock(ProductClient.class);
    private final OrderService service = new OrderService(null, cache, objectMapper, productClient);

    @Test
    void returnsCachedPriceWithoutCallingProductService() {
        when(cache.get("product-price:sku-1")).thenReturn("19.99");

        BigDecimal result = service.resolveProductPrice("sku-1");

        assertEquals(new BigDecimal("19.99"), result);
        verify(productClient, never()).getById(any());
    }

    @Test
    void fallsThroughToProductServiceOnCacheMissAndCachesJustThePrice() {
        when(cache.get("product-price:sku-2")).thenReturn(null);
        ProductProfile fetched = new ProductProfile("sku-2", "Hoodie", new BigDecimal("39.99"), 3, "2026-07-21T00:00:00Z");
        when(productClient.getById("sku-2")).thenReturn(fetched);

        BigDecimal result = service.resolveProductPrice("sku-2");

        assertEquals(new BigDecimal("39.99"), result);
        verify(cache, times(1)).set("product-price:sku-2", "39.99", 60);
    }

    @Test
    void returnsNullOnGenuineNotFoundWithoutCachingAnything() {
        when(cache.get("product-price:ghost-sku")).thenReturn(null);
        when(productClient.getById("ghost-sku")).thenThrow(new NotFoundException());

        BigDecimal result = service.resolveProductPrice("ghost-sku");

        assertNull(result);
        verify(cache, never()).set(any(), any(), anyInt());
    }
}
