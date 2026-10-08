package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.catalog.application.CatalogWorkbook;
import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.ClosingView;
import be.enrosed.inventory.adapter.in.rest.StockClosingDtos.Notice;
import be.enrosed.inventory.application.ClosingNotices;
import be.enrosed.inventory.application.InventoryRefusal;
import be.enrosed.inventory.application.StockClosingFinalizer;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The year-end closing once the concept is ready: make it final, start a correction, download the two files. */
@Path("/api/stock-closings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class StockClosingFinalResource {

    public record FinalizeRequest(String dataSha256, String signerName) {}

    public record VersionRequest(String reason) {}

    private final StockClosingFinalizer finalizer;

    public StockClosingFinalResource(StockClosingFinalizer finalizer) {
        this.finalizer = finalizer;
    }

    @POST
    @Path("/{id}/finalize")
    public ClosingView finalizeClosing(@PathParam("id") long id, FinalizeRequest request) {
        FinalizeRequest asked = request == null ? new FinalizeRequest(null, null) : request;
        try {
            return ClosingView.from(finalizer.makeFinal(id, asked.dataSha256(), asked.signerName()));
        } catch (InventoryRefusal refusal) {
            if (!(refusal.details().get("notices") instanceof List<?> blockers)) throw refusal;
            /* The points that block, in the shape the screen already reads them in. */
            Map<String, Object> details = new LinkedHashMap<>(refusal.details());
            details.put("notices", blockers.stream().map(notice -> Notice.from((ClosingNotices.Notice) notice)).toList());
            throw new InventoryRefusal(refusal.code(), refusal.getMessage(), details);
        }
    }

    @POST
    @Path("/{id}/versions")
    public Response newVersion(@PathParam("id") long id, VersionRequest request) {
        try {
            return Response.status(Response.Status.CREATED)
                    .entity(ClosingView.from(finalizer.newVersion(id, request == null ? null : request.reason()))).build();
        } catch (BusinessRuleException | NotFoundException refused) {
            throw refused;
        } catch (PersistenceException raced) {
            /* Two corrections of one year at once: the unique index on year and version let one through. */
            throw new InventoryRefusal("CONCEPT_BESTAAT", "Voor " + finalizer.closingYear(id) + " staat al een concept open");
        }
    }

    @GET
    @Path("/{id}/pdf")
    @Produces(StockClosingFinalizer.PDF_MEDIA_TYPE)
    public Response pdf(@PathParam("id") long id) {
        return download(() -> finalizer.pdf(id));
    }

    @GET
    @Path("/{id}/xlsx")
    @Produces(CatalogWorkbook.MEDIA_TYPE)
    public Response xlsx(@PathParam("id") long id) {
        return download(() -> finalizer.xlsx(id));
    }

    private static Response download(Supplier<StockClosingFinalizer.File> source) {
        StockClosingFinalizer.File file;
        try {
            file = source.get();
        } catch (NotFoundException missing) {
            /* Answered here as JSON: on a file route the shared mapper would label its message as the file type. */
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", 404);
            body.put("message", missing.getMessage());
            body.put("timestamp", Instant.now().toString());
            return Response.status(404).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
        }
        return Response.ok(file.content(), file.mediaType())
                .header("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }
}
