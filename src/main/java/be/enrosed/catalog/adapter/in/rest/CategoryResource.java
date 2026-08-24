package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CategoryService;
import be.enrosed.catalog.domain.Category;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CategoryResource {

    private final CategoryService categories;

    public CategoryResource(CategoryService categories) {
        this.categories = categories;
    }

    @GET
    public List<Category> list() {
        return categories.list();
    }

    @POST
    public Response create(Category category) {
        return Response.status(Response.Status.CREATED).entity(categories.create(category)).build();
    }

    @PUT
    @Path("/{id}")
    public Category update(@PathParam("id") long id, Category category) {
        return categories.update(id, category);
    }

    @PUT
    @Path("/order")
    public List<Category> reorder(List<Long> ids) {
        return categories.reorder(ids);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        categories.delete(id);
        return Response.noContent().build();
    }
}
