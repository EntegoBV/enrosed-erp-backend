package be.enrosed.account;

import be.enrosed.account.CustomerSessionGuard.CustomerSession;
import be.enrosed.publicform.ClientIdentityResolver;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormValidationException;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import be.enrosed.sales.application.AccountDocuments;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.shared.Language;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * "My orders" of a logged-in customer: the delivery prefill, the list, one order or
 * quotation in full, and the PDF. Reading only. Every method asks the session guard
 * first and takes the customer from the session alone. Nothing the sales core throws
 * leaves as it is: its sentences are Dutch and written for staff, so the customer gets
 * a neutral answer and the cause goes to the log.
 */
@Path("/api/v1/public/account/documents")
@PermitAll
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public class CustomerDocumentResource {
    private static final Logger LOG = Logger.getLogger(CustomerDocumentResource.class);
    private static final int DEFAULTS_PER_ACCOUNT = 600;
    private static final int READS_PER_ACCOUNT = 240;
    private static final int PDFS_PER_ACCOUNT = 30;

    private final AccountDocuments documents;
    private final CustomerSessionGuard guard;
    private final PublicFormRateLimiter rateLimiter;
    private final ClientIdentityResolver identities;

    @Context
    HttpServerRequest httpRequest;

    public CustomerDocumentResource(AccountDocuments documents, CustomerSessionGuard guard,
                                    PublicFormRateLimiter rateLimiter,
                                    ClientIdentityResolver identities) {
        this.documents = documents;
        this.guard = guard;
        this.rateLimiter = rateLimiter;
        this.identities = identities;
    }

    /**
     * The prefill of the delivery step, and the question the quote page asks to learn whether
     * this backend takes orders. It has a budget of its own, so browsing the history can never
     * make the page fall back to a quote request.
     */
    @GET
    @Path("/delivery-defaults")
    public Response deliveryDefaults() {
        CustomerSession session = guard.require(httpRequest);
        if (!documents.orderingEnabled()) return orderingOff();
        rateLimiter.checkKey(PublicFormAction.ACCOUNT_ORDER_DEFAULTS, "ACCOUNT",
                String.valueOf(session.accountId()), DEFAULTS_PER_ACCOUNT);
        try {
            return noStore(Response.ok(documents.deliveryDefaults(session.customerId())));
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Delivery defaults of customer %d could not be read", session.customerId());
            return unavailable();
        }
    }

    @GET
    public Response list(@QueryParam("kind") String kind, @QueryParam("cursor") String cursor,
                         @QueryParam("limit") String limit) {
        CustomerSession session = guard.require(httpRequest);
        if (!documents.orderingEnabled()) return orderingOff();
        countRead(session);
        Map<String, String> errors = new LinkedHashMap<>();
        AccountDocuments.ListKind parsedKind = kind(kind, errors);
        Long parsedCursor = cursor(cursor, errors);
        int parsedLimit = limit(limit, errors);
        if (!errors.isEmpty()) throw new PublicFormValidationException(errors);
        try {
            return noStore(Response.ok(documents.page(parsedKind, parsedCursor, parsedLimit, session.customerId())));
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Documents of customer %d could not be listed", session.customerId());
            return unavailable();
        }
    }

    @GET
    @Path("/{id:\\d{1,18}}")
    public Response detail(@PathParam("id") long id) {
        CustomerSession session = guard.require(httpRequest);
        if (!documents.orderingEnabled()) return orderingOff();
        countRead(session);
        try {
            return documents.detail(id, session.customerId())
                    .map(detail -> noStore(Response.ok(detail)))
                    .orElseGet(CustomerDocumentResource::notFound);
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Document %d could not be shown to its customer", id);
            return unavailable();
        }
    }

    /**
     * The answer is a PDF or, for every refusal, the usual JSON error. Each response names its
     * own type, and the limits are answered here: a mapper would not know which of the two to write.
     */
    @GET
    @Path("/{id:\\d{1,18}}/pdf")
    @Produces({"application/pdf", MediaType.APPLICATION_JSON})
    public Response pdf(@PathParam("id") long id, @QueryParam("language") String language) {
        CustomerSession session = guard.require(httpRequest);
        if (!documents.orderingEnabled()) return orderingOff();
        try {
            rateLimiter.checkKey(PublicFormAction.ACCOUNT_ORDER_PDF, "ACCOUNT",
                    String.valueOf(session.accountId()), PDFS_PER_ACCOUNT);
            rateLimiter.checkIp(PublicFormAction.ACCOUNT_ORDER_PDF, identities.resolve(httpRequest));
        } catch (PublicFormRateLimitException exception) {
            return error(429, "RATE_LIMITED", "Too many requests; try again later", Map.of())
                    .header("Retry-After", exception.retryAfterSeconds()).build();
        }
        Language wanted;
        try {
            wanted = Language.requireSupported(language, null);
        } catch (IllegalArgumentException exception) {
            return error(422, "VALIDATION_ERROR", "The request contains invalid or missing fields",
                    Map.of("language", "UNSUPPORTED")).build();
        }
        try {
            Optional<QuoteDocumentRenderer.Document> document = documents.pdf(id, session.customerId(), wanted);
            if (document.isEmpty()) return notFound();
            return Response.ok(document.get().content(), "application/pdf")
                    .header("Content-Disposition", "attachment; filename=\"" + document.get().filename() + "\"")
                    .header("Cache-Control", "no-store")
                    .build();
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "The PDF of document %d could not be made for its customer", id);
            return unavailable();
        }
    }

    /** List and detail share one budget per account; the limit is answered by the existing mapper. */
    private void countRead(CustomerSession session) {
        rateLimiter.checkKey(PublicFormAction.ACCOUNT_ORDER_READ, "ACCOUNT",
                String.valueOf(session.accountId()), READS_PER_ACCOUNT);
    }

    private static AccountDocuments.ListKind kind(String value, Map<String, String> errors) {
        try {
            return AccountDocuments.ListKind.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.put("kind", "INVALID");
            return null;
        }
    }

    private static Long cursor(String value, Map<String, String> errors) {
        if (value == null || value.isBlank()) return null;
        if (value.matches("\\d{1,18}") && Long.parseLong(value) > 0) return Long.parseLong(value);
        errors.put("cursor", "INVALID");
        return null;
    }

    private static int limit(String value, Map<String, String> errors) {
        if (value == null || value.isBlank()) return AccountDocuments.DEFAULT_PAGE;
        if (value.matches("\\d{1,2}") && Integer.parseInt(value) >= 1
                && Integer.parseInt(value) <= AccountDocuments.MAX_PAGE) return Integer.parseInt(value);
        errors.put("limit", "INVALID");
        return AccountDocuments.DEFAULT_PAGE;
    }

    /** What an older backend answers too: the website then offers the quote request. */
    private static Response orderingOff() {
        return error(404, "NOT_FOUND", "Not found", Map.of()).build();
    }

    /** The same answer for a missing id, a document of another customer, a hidden one and one without the facet asked. */
    private static Response notFound() {
        return error(404, "ORDER_NOT_FOUND", "Not found", Map.of()).build();
    }

    private static Response unavailable() {
        return error(409, "DOCUMENT_UNAVAILABLE", "The document cannot be shown right now", Map.of()).build();
    }

    private static Response.ResponseBuilder error(int status, String code, String message,
                                                  Map<String, String> fieldErrors) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE)
                .header("Cache-Control", "no-store")
                .entity(new PublicQuoteDtos.ErrorResponse(code, message, fieldErrors));
    }

    private static Response noStore(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store").build();
    }
}
