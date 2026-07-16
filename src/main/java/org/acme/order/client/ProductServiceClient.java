package org.acme.order.client;

import org.acme.order.dto.ReserveStockRequest;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@RegisterRestClient(configKey = "product-service")
@Path("/api/skus")
@Consumes(MediaType.APPLICATION_JSON)
public interface ProductServiceClient {

    /** 200 = all items reserved; 409 = insufficient stock (nothing decremented). */
    @POST
    @Path("/reserve")
    Response reserve(ReserveStockRequest request);
}
