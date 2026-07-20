package org.acme.order.resource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.acme.order.dto.CreateOrderRequest;
import org.acme.order.dto.OrderAcceptedResponse;
import org.acme.order.entity.Order;
import org.acme.order.exception.ProductNotFoundException;
import org.acme.order.exception.ProductServiceUnavailableException;
import org.acme.order.service.OrderService;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

// @RunOnVirtualThread (below, on each method): this endpoint does blocking work —
// a Postgres INSERT, waiting for RabbitMQ to confirm, maybe an HTTP call to
// product-service. Normally that would tie up one of a small, fixed pool of OS
// threads for the whole request. A virtual thread (Java 21+) is cheap and
// disposable — thousands can exist at once — so blocking here doesn't cost what
// it used to. This is Quarkus's answer to "how do we handle lots of concurrent
// slow requests without an async/callback style like Node's". You don't need to
// change how you write the method body — it's just this one annotation.
@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    private static final Logger LOG = Logger.getLogger(OrderResource.class);

    @Inject
    OrderService orderService;

    @POST
    @RunOnVirtualThread
    public Response create(CreateOrderRequest request) {
        String error = request.validationError();
        if (error != null) {
            return Response.status(400).entity(Map.of("error", error)).build();
        }
        try {
            Order order = orderService.submit(request);
            return Response.status(201).entity(OrderAcceptedResponse.from(order)).build();
        } catch (ProductNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (ProductServiceUnavailableException e) {
            // this was silent before (found the hard way — a Redis pool exhaustion
            // problem cascaded through here with nothing logged, making it much
            // slower to diagnose than it needed to be)
            LOG.warnf("product-service unavailable for productId=%s: %s",
                    request.productId(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return Response.status(503).entity(Map.of("error", "product-service unavailable")).build();
        }
    }

    @GET
    @Path("/{id}")
    @RunOnVirtualThread
    public Response get(@PathParam("id") UUID id) {
        Order order = orderService.findById(id);
        if (order == null) {
            return Response.status(404).entity(Map.of("error", "order not found")).build();
        }
        return Response.ok(OrderAcceptedResponse.from(order)).build();
    }

    @GET
    @Path("/product/{productId}")
    @RunOnVirtualThread
    public List<OrderAcceptedResponse> getByProductId(@PathParam("productId") String productId) {
        return orderService.findByProductId(productId);
    }
}
