package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/customers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CustomerResource {

    private final CustomerService customers;

    public CustomerResource(CustomerService customers) {
        this.customers = customers;
    }

    @GET
    public List<Customer> list() {
        return customers.list();
    }

    @GET
    @Path("/{id}")
    public Customer get(@PathParam("id") long id) {
        return customers.get(id);
    }

    @POST
    public Response create(Customer customer) {
        return Response.status(Response.Status.CREATED).entity(customers.create(customer)).build();
    }

    @PUT
    @Path("/{id}")
    public Customer update(@PathParam("id") long id, Customer customer) {
        return customers.update(id, customer);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        customers.delete(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/usage")
    public Map<String, Long> usage(@PathParam("id") long id) {
        return Map.of("orders", customers.orderCount(id));
    }
}
