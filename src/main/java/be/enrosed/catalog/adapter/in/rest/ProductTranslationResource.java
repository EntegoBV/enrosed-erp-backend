package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.ProductTranslationCsv;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;

/**
 * Vertalingen als CSV eruit en er weer in.
 *
 * Vertalen gebeurt in een spreadsheet, vaak door iemand buiten het bedrijf. Het
 * bestand eruit halen, laten invullen en terugzetten is daarvoor handiger dan
 * een scherm waarin je product per product moet klikken.
 */
@Path("/api/products/translations")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProductTranslationResource {

    private final ProductTranslationCsv csv;

    public ProductTranslationResource(ProductTranslationCsv csv) {
        this.csv = csv;
    }

    @GET
    @Path("/csv")
    @Produces("text/csv")
    public Response export() {
        byte[] content = csv.export();
        String filename = "enrosed-vertalingen-" + LocalDate.now() + ".csv";
        return Response.ok(content)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @POST
    @Path("/csv")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public ProductTranslationCsv.ImportResult importCsv(@RestForm("file") FileUpload file)
            throws IOException {
        if (file == null) {
            throw new BadRequestException("Geen bestand meegestuurd");
        }
        try (InputStream data = Files.newInputStream(file.uploadedFile())) {
            return csv.importFrom(data);
        }
    }
}
