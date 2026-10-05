package be.enrosed.account;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The Websitelogin block on the customer sheet. The service methods commit first and never
 * send mail; only then is the invitation delivered, so a rollback leaves no live link in a
 * mailbox and a mail failure never undoes the login.
 */
@Path("/api/customer-logins")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Produces(MediaType.APPLICATION_JSON)
public class CustomerLoginResource {
    private final CustomerAccountService accounts;

    public CustomerLoginResource(CustomerAccountService accounts) {
        this.accounts = accounts;
    }

    @GET
    public Response list(@QueryParam("customerId") Long customerId) {
        if (customerId == null) throw new BadRequestException("customerId is verplicht");
        return noStore(Response.ok(accounts.forCustomer(customerId)));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response grant(AccountDtos.GrantRequest request) {
        if (request == null || request.customerId() == null) {
            throw new BadRequestException("customerId is verplicht");
        }
        CustomerAccountService.Grant grant = accounts.grantByStaff(request.customerId(), request.email());
        return noStore(Response.status(Response.Status.CREATED).entity(delivered(grant)));
    }

    @POST
    @Path("/{id}/invitation")
    public Response invitation(@PathParam("id") long id) {
        return noStore(Response.ok(delivered(accounts.reissueByStaff(id))));
    }

    @POST
    @Path("/{id}/withdraw")
    public Response withdraw(@PathParam("id") long id) {
        return noStore(Response.ok(accounts.withdraw(id)));
    }

    /** After the commit: send the link, then read the login as it now stands. */
    private AccountDtos.GrantResponse delivered(CustomerAccountService.Grant grant) {
        AccountDtos.Invitation invitation = accounts.deliver(grant);
        return new AccountDtos.GrantResponse(accounts.view(grant.accountId()), invitation);
    }

    private static Response noStore(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store").build();
    }
}
