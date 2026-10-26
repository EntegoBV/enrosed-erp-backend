package be.enrosed.finance.invoices;

import be.enrosed.finance.odoo.OdooDocumentService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Facturen: one read-only list over every invoice ENROSED and Odoo hold, one row by key, file previews and the two
 * money-free mark routes. Request errors answer 400 {status, code, message}; Odoo failures keep OdooResource's mapper.
 */
@Path("/api/invoice-finder")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class InvoiceFinderResource {
    @Inject InvoiceFinderService finder;
    @Inject OdooInvoices odoo;

    @GET
    public InvoiceFinderService.Page list(@QueryParam("direction") String direction, @QueryParam("scope") String scope,
                                          @QueryParam("q") String q, @QueryParam("from") String from,
                                          @QueryParam("to") String to, @QueryParam("orderId") String orderId,
                                          @QueryParam("cursor") String cursor, @QueryParam("limit") String limit) {
        return finder.find(new InvoiceFinderService.Query(direction, scope, q, date(from), date(to), orderId(orderId),
                cursor == null || cursor.isBlank() ? null : cursor.strip(), limit(limit)));
    }

    @GET
    @Path("/rows/{key}")
    public InvoiceRow row(@PathParam("key") String key) {
        return finder.row(key);
    }

    @PUT
    @Path("/odoo/{moveId}/mark")
    public InvoiceRow mark(@PathParam("moveId") long moveId, InvoiceFinderService.MarkRequest request) {
        return finder.mark(moveId, request);
    }

    @DELETE
    @Path("/odoo/{moveId}/mark")
    public InvoiceRow clearMark(@PathParam("moveId") long moveId) {
        return finder.clearMark(moveId);
    }

    @GET
    @Path("/odoo/{moveId}/attachments/{attachmentId}")
    @Produces(MediaType.WILDCARD)
    public Response attachment(@PathParam("moveId") long moveId, @PathParam("attachmentId") long attachmentId) {
        OdooDocumentService.Download file = odoo.attachment(moveId, attachmentId);
        String type = file.mimeType() == null || file.mimeType().isBlank() ? "application/octet-stream" : file.mimeType();
        return file(file.bytes(), type, safeName(file.name(), "odoo-bijlage"), false);
    }

    @GET
    @Path("/odoo/{moveId}/attachments/{attachmentId}/pdf")
    @Produces(MediaType.WILDCARD)
    public Response attachmentPdf(@PathParam("moveId") long moveId, @PathParam("attachmentId") long attachmentId) {
        OdooDocumentService.Download file = odoo.attachment(moveId, attachmentId);
        return pdf(file.name(), file.mimeType(), file.bytes(), "odoo-bijlage");
    }

    @GET
    @Path("/media/{assetId}/pdf")
    @Produces(MediaType.WILDCARD)
    public Response mediaPdf(@PathParam("assetId") long assetId) {
        InvoiceFinderService.MediaFile file = finder.mediaFile(assetId);
        if (file.bytes() == null) throw InvoiceFinderErrors.invalid("FILE_TOO_LARGE");
        return pdf(file.name(), file.contentType(), file.bytes(), "document");
    }

    /** The file itself when it is a PDF, else the PDF inside a UBL/CII XML; 404 NO_PDF when there is none. */
    static Response pdf(String name, String contentType, byte[] bytes, String fallback) {
        if (bytes == null || bytes.length == 0) throw InvoiceFinderErrors.invalid("NO_PDF");
        if (isPdf(bytes)) return file(bytes, "application/pdf", pdfName(name, fallback), true);
        byte[] head = Arrays.copyOf(bytes, Math.min(bytes.length, 512));
        if (EmbeddedPdf.looksLikeXml(name, contentType, head)) {
            Optional<EmbeddedPdf.Extracted> embedded = EmbeddedPdf.fromXml(bytes);
            if (embedded.isPresent()) {
                String inner = embedded.get().filename();
                return file(embedded.get().pdf(), "application/pdf",
                        inner == null || inner.isBlank() ? pdfName(name, fallback) : safeName(inner, fallback + ".pdf"), true);
            }
        }
        throw InvoiceFinderErrors.invalid("NO_PDF");
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-");
    }

    private static Response file(byte[] bytes, String type, String name, boolean inline) {
        return Response.ok(bytes, type)
                .header("Content-Disposition", (inline ? "inline" : "attachment") + "; filename=\"" + name + "\"")
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    static String safeName(String name, String fallback) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9._ -]", "_").strip();
        return safe.isBlank() ? fallback : safe;
    }

    private static String pdfName(String name, String fallback) {
        String safe = safeName(name, fallback);
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        return (base.isBlank() ? fallback : base) + ".pdf";
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException invalid) {
            throw InvoiceFinderErrors.invalid("INVALID_PERIOD");
        }
    }

    private static Long orderId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long id = Long.parseLong(value.strip());
            if (id <= 0) throw InvoiceFinderErrors.invalid("INVALID_ORDER");
            return id;
        } catch (NumberFormatException invalid) {
            throw InvoiceFinderErrors.invalid("INVALID_ORDER");
        }
    }

    private static int limit(String value) {
        if (value == null || value.isBlank()) return InvoiceFinderService.DEFAULT_LIMIT;
        try {
            return Integer.parseInt(value.strip().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException invalid) {
            throw InvoiceFinderErrors.invalid("INVALID_LIMIT");
        }
    }
}
