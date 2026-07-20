package org.acme.order.exception;

// Redis was a miss/error (fail-open, §7.4) AND the product-service fallback
// call itself failed — distinct from ProductNotFoundException so the
// resource can answer 503 instead of misreporting "product not found".
public class ProductServiceUnavailableException extends RuntimeException {
    public ProductServiceUnavailableException(Throwable cause) {
        super("product-service unavailable", cause);
    }
}
