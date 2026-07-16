package org.acme.order.resource;

import java.util.Map;
import java.util.UUID;

import org.acme.order.dto.CreateOrderRequest;
import org.acme.order.dto.OrderAcceptedResponse;
import org.acme.order.entity.Order;
import org.acme.order.service.InsufficientStockException;
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

@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

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
            return Response.status(201).entity(OrderAcceptedResponse.of(order)).build();
        } catch (InsufficientStockException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}")
    @RunOnVirtualThread
    public Response get(@PathParam("id") UUID id) {
        Order order = orderService.findWithItems(id);
        if (order == null) {
            return Response.status(404).entity(Map.of("error", "order not found")).build();
        }
        return Response.ok(order).build();
    }
}
