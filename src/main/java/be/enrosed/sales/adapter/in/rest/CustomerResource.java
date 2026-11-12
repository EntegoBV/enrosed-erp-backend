package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.CustomerView;
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

    /** Every customer; a customer a partner added in the partner portal carries partnerOrigin (additive). */
    @GET
    public List<CustomerView> list() {
        return customers.listViews();
    }

    @GET
    @Path("/{id}")
    public CustomerView get(@PathParam("id") long id) {
        return customers.view(customers.get(id));
    }

    @POST
    public Response create(Customer customer) {
        return Response.status(Response.Status.CREATED).entity(customers.view(customers.create(customer))).build();
    }

    @PUT
    @Path("/{id}")
    public CustomerView update(@PathParam("id") long id, Customer customer) {
        return customers.view(customers.update(id, customer));
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
