package be.enrosed.tica;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/tica/settlements") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(be.enrosed.shared.security.AdminIdentityProvider.ADMIN_ROLE)
public class TicaSettlementResource {
    private final TicaSettlementService service;
    public TicaSettlementResource(TicaSettlementService service) { this.service = service; }
    public record BankLink(Long bankStatementLineId) {}
    @GET public List<TicaSettlementService.Dto> list() { return service.list(); }
    @GET @Path("/{id}") public TicaSettlementService.Dto get(@PathParam("id") long id) { return service.get(id); }
    @POST public TicaSettlementService.Dto create(TicaSettlementService.Request request) { return service.create(request); }
    @PUT @Path("/{id}") public TicaSettlementService.Dto update(@PathParam("id") long id, TicaSettlementService.Request request) { return service.update(id, request); }
    @POST @Path("/{id}/confirm") public TicaSettlementService.Dto confirm(@PathParam("id") long id) { return service.confirm(id); }
    @POST @Path("/{id}/bank-link") public TicaSettlementService.Dto link(@PathParam("id") long id, BankLink request) {
        if (request == null || request.bankStatementLineId == null) throw new BadRequestException("Kies een banktransactie.");
        return service.linkBank(id, request.bankStatementLineId);
    }
    @DELETE @Path("/{id}/bank-link") public TicaSettlementService.Dto unlink(@PathParam("id") long id) { return service.unlinkBank(id); }
}
