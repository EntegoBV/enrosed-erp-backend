package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.application.CatalogWorkbook;
import be.enrosed.shared.BusinessRuleException;
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

/** Safe bulk-editable catalogue fields and translations in one native Excel workbook. */
@Path("/api/products/workbook")
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CatalogWorkbookResource {

    private static final long MAX_FILE_BYTES = 15L * 1024 * 1024;

    private final CatalogWorkbook workbook;

    public CatalogWorkbookResource(CatalogWorkbook workbook) {
        this.workbook = workbook;
    }

    @GET
    @Produces(CatalogWorkbook.MEDIA_TYPE)
    public Response export() {
        String filename = "enrosed-catalogus-" + LocalDate.now() + ".xlsx";
        return Response.ok(workbook.export())
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public CatalogWorkbook.ImportResult importWorkbook(@RestForm("file") FileUpload file)
            throws IOException {
        if (file == null) {
            throw new BadRequestException("Geen Excel-bestand meegestuurd");
        }
        if (Files.size(file.uploadedFile()) > MAX_FILE_BYTES) {
            throw new BusinessRuleException("Het Excel-bestand is groter dan 15 MB");
        }
        try (InputStream data = Files.newInputStream(file.uploadedFile())) {
            return workbook.importFrom(data);
        }
    }
}
