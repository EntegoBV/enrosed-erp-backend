package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.ProductCsv;
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
 * The catalogue's master data as CSV, out and back in.
 *
 * Fixing forty HS codes one edit screen at a time never gets finished; in a
 * spreadsheet it is ten minutes. Rows match on SKU, unknown SKUs are
 * reported rather than created, and empty cells leave fields untouched.
 */
@Path("/api/products/csv")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class ProductCsvResource {

    private final ProductCsv csv;

    public ProductCsvResource(ProductCsv csv) {
        this.csv = csv;
    }

    @GET
    @Produces("text/csv")
    public Response export() {
        byte[] content = csv.export();
        String filename = "enrosed-producten-" + LocalDate.now() + ".csv";
        return Response.ok(content)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public ProductCsv.ImportResult importCsv(@RestForm("file") FileUpload file) throws IOException {
        if (file == null) {
            throw new BadRequestException("Geen bestand meegestuurd");
        }
        try (InputStream data = Files.newInputStream(file.uploadedFile())) {
            return csv.importFrom(data);
        }
    }
}
