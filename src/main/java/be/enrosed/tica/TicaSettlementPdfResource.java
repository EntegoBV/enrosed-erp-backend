package be.enrosed.tica;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

@Path("/api/tica/settlements") @Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class TicaSettlementPdfResource {
    private final TicaSettlementPdfService service;
    public TicaSettlementPdfResource(TicaSettlementPdfService service) { this.service = service; }
    @POST @Path("/document-preview") @Consumes(MediaType.MULTIPART_FORM_DATA)
    public TicaSettlementPdfService.Preview preview(@RestForm("file") FileUpload file) throws IOException {
        if (file == null || Files.size(file.uploadedFile()) > TicaSettlementPdfParser.MAX_BYTES)
            throw new BusinessRuleException("Kies een PDF van maximaal 3 MB.");
        return service.preview(Files.readAllBytes(file.uploadedFile()), file.fileName());
    }
    @GET @Path("/documents/{id}") @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response document(@PathParam("id") UUID id) {
        var file = service.document(id);
        return Response.ok(file.bytes(), file.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + file.fileName() + "\"")
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff").build();
    }
}
