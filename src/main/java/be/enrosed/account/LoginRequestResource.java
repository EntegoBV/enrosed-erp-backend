package be.enrosed.account;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The page Login-aanvragen. A decision commits first and never sends mail; only then is the
 * link delivered, so a rollback leaves no live link in a mailbox and a mail failure never
 * undoes an approval. The actor comes from the session, never from the body.
 */
@Path("/api/login-requests")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
@Produces(MediaType.APPLICATION_JSON)
public class LoginRequestResource {
    private final LoginRequestService requests;
    private final CustomerAccountService accounts;
    private final AccountHousekeeping housekeeping;

    public LoginRequestResource(LoginRequestService requests, CustomerAccountService accounts,
                                AccountHousekeeping housekeeping) {
        this.requests = requests;
        this.accounts = accounts;
        this.housekeeping = housekeeping;
    }

    @GET
    public Response list(@QueryParam("status") String status,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("size") @DefaultValue("50") int size) {
        return noStore(Response.ok(requests.list(status, page, size)));
    }

    @GET
    @Path("/summary")
    public Response summary() {
        return noStore(Response.ok(requests.summary()));
    }

    @GET
    @Path("/{id}")
    public Response detail(@PathParam("id") long id) {
        return noStore(Response.ok(requests.detail(id)));
    }

    @POST
    @Path("/{id}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("id") long id, LoginRequestDtos.ApproveRequest body) {
        LoginRequestService.Approved approved;
        try {
            approved = requests.approve(id, body);
        } catch (RuntimeException failure) {
            /* Someone gave this address a login at the same moment; the approval rolled back. */
            if (CustomerAccountService.isDuplicateLogin(failure)) {
                throw new BusinessRuleException(CustomerAccountService.DUPLICATE_LOGIN);
            }
            throw failure;
        }
        /* After the commit: send the link, then read the login as it now stands. */
        AccountDtos.Invitation invitation = accounts.deliver(approved.grant());
        LoginRequestDtos.ApproveResponse response = new LoginRequestDtos.ApproveResponse(
                approved.request(), accounts.view(approved.grant().accountId()), invitation);
        housekeeping.purge();
        return noStore(Response.ok(response));
    }

    @POST
    @Path("/{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reject(@PathParam("id") long id, LoginRequestDtos.RejectRequest body) {
        LoginRequestDtos.LoginRequestView rejected = requests.reject(id, body == null ? null : body.note());
        housekeeping.purge();
        return noStore(Response.ok(rejected));
    }

    private static Response noStore(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store").build();
    }
}
