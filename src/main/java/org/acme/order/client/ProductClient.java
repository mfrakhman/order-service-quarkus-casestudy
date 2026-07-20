package org.acme.order.client;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

// Synchronous cache-miss fallback only (ARCHITECTURE.md §7.3) — never on the
// hot path once Redis is warm. Base URL: quarkus.rest-client.product-service.url.
//
// This whole file is just an interface — no implementation, no HTTP client code.
// @RegisterRestClient tells Quarkus "generate a real HTTP client for this
// interface at startup." configKey links to the quarkus.rest-client.product-service.*
// settings in application.properties (base URL, timeouts). Calling
// productClient.getById("sku-1") anywhere in the app actually does
// `GET {base-url}/api/products/sku-1` and parses the JSON response into a
// ProductProfile — same end result as calling axios/fetch by hand, just declared
// instead of written out.
@RegisterRestClient(configKey = "product-service")
@Path("/api/products")
public interface ProductClient {

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    ProductProfile getById(@PathParam("id") String id);
}
