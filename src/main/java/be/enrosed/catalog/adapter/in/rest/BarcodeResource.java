package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.BarcodePoolService;
import be.enrosed.catalog.application.BarcodeValidator;
import be.enrosed.catalog.application.Ean13Image;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** The company's EAN list and barcode images. */
@Path("/api/barcodes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class BarcodeResource {

    private final BarcodePoolService pool;
    private final BarcodeValidator validator;

    public BarcodeResource(BarcodePoolService pool, BarcodeValidator validator) {
        this.pool = pool;
        this.validator = validator;
    }

    @GET
    @Path("/pool")
    public List<String> pool() {
        return pool.free();
    }

    public record AddRequest(String codes) {}

    @POST
    @Path("/pool")
    public BarcodePoolService.Intake add(AddRequest request) {
        return pool.add(request == null ? "" : request.codes());
    }

    @DELETE
    @Path("/pool/{code}")
    public Response remove(@PathParam("code") String code) {
        pool.remove(code);
        return Response.noContent().build();
    }

    public record Next(String code, long remaining) {}

    /** The next free code, reserved in the form; it leaves the list when the product is saved. */
    @GET
    @Path("/pool/next")
    public Next next() {
        return new Next(pool.next(), pool.count());
    }

    /** Print-ready EAN-13, 300 dpi unless asked otherwise. */
    @GET
    @Path("/{code}/image.png")
    @Produces("image/png")
    public Response image(@PathParam("code") String code, @QueryParam("dpi") @DefaultValue("300") int dpi) {
        BarcodeValidator.Result check = validator.validate(code);
        if (!check.valid()) throw new BusinessRuleException("Geen geldige barcode: " + check.message());
        if (code.trim().length() != 13) throw new BusinessRuleException("Alleen EAN-13 wordt getekend");
        int resolution = Math.max(150, Math.min(1200, dpi));
        return Response.ok(Ean13Image.png(code.trim(), resolution))
                .header("Content-Disposition", "attachment; filename=\"EAN-" + code.trim() + ".png\"")
                .build();
    }
}
