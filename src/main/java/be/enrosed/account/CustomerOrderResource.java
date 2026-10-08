package be.enrosed.account;

import be.enrosed.account.CustomerSessionGuard.CustomerSession;
import be.enrosed.publicform.ClientIdentityResolver;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormBodyLimited;
import be.enrosed.publicform.PublicFormIdempotencyService;
import be.enrosed.publicform.PublicFormPurpose;
import be.enrosed.publicform.PublicFormRateLimitException;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.publicform.PublicFormSecurityService;
import be.enrosed.publicform.PublicFormServiceUnavailableException;
import be.enrosed.publicform.PublicFormValidationException;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.CancelRequest;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderPreviewRequest;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderReceipt;
import be.enrosed.sales.adapter.in.rest.AccountOrderDtos.OrderRequest;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import be.enrosed.sales.application.AccountDocuments;
import be.enrosed.sales.application.WebOrderRefusal;
import be.enrosed.sales.application.WebOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The order of a logged-in customer: the estimate, placing it, changing it and cancelling
 * it. Writing only; reading is CustomerDocumentResource. Every method asks the session guard
 * first, also before a stored answer is replayed, and takes customer, login and e-mail from
 * the session alone. Nothing the sales core throws leaves as it is: its sentences are Dutch
 * and written for staff, so the customer gets a neutral answer and the cause goes to the log.
 */
@Path("/api/v1/public/account/orders")
@PermitAll
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public class CustomerOrderResource {
    private static final Logger LOG = Logger.getLogger(CustomerOrderResource.class);
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int WRITES_PER_ACCOUNT = 20;

    private final WebOrderService orders;
    private final AccountDocuments documents;
    private final CustomerSessionGuard guard;
    private final PublicFormSecurityService security;
    private final PublicFormRateLimiter rateLimiter;
    private final PublicFormIdempotencyService idempotency;
    private final ClientIdentityResolver identities;
    private final ObjectMapper json;

    @Context
    HttpServerRequest httpRequest;

    public CustomerOrderResource(WebOrderService orders, AccountDocuments documents,
                                 CustomerSessionGuard guard, PublicFormSecurityService security,
                                 PublicFormRateLimiter rateLimiter,
                                 PublicFormIdempotencyService idempotency,
                                 ClientIdentityResolver identities, ObjectMapper json) {
        this.orders = orders;
        this.documents = documents;
        this.guard = guard;
        this.security = security;
        this.rateLimiter = rateLimiter;
        this.idempotency = idempotency;
        this.identities = identities;
        this.json = json;
    }

    @POST
    @Path("/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = OrderPreviewRequest.class)
    public Response preview(OrderPreviewRequest request) {
        CustomerSession session = guard.require(httpRequest);
        if (!documents.orderingEnabled()) return orderingOff();
        rateLimiter.checkIp(PublicFormAction.QUOTE_PREVIEW, identities.resolve(httpRequest));
        try {
            return noStore(Response.ok(orders.previewByCustomer(request, session)));
        } catch (WebOrderRefusal refusal) {
            return refused(refusal);
        } catch (PublicFormValidationException | PublicFormRateLimitException
                 | PublicFormServiceUnavailableException expected) {
            throw expected;
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "The order estimate of customer %d could not be made", session.customerId());
            return error(409, "DOCUMENT_UNAVAILABLE", "The document cannot be shown right now", Map.of());
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = OrderRequest.class)
    public Response place(OrderRequest request, @HeaderParam("Idempotency-Key") String idempotencyKey) {
        /* Before the replay: a stored answer belongs to a login that may have been withdrawn. */
        CustomerSession session = guard.require(httpRequest);
        if (!documents.orderingEnabled()) return orderingOff();
        String fingerprint = fingerprint(session.accountId(), "PLACE", null, null, request);
        try {
            var replay = idempotency.replay(PublicFormPurpose.ACCOUNT_ORDER, idempotencyKey,
                    fingerprint, OrderReceipt.class);
            if (replay.isPresent()) return receipt(201, replay.get());
            rateLimiter.checkIp(PublicFormAction.ACCOUNT_QUOTE_SUBMIT, identities.resolve(httpRequest));
            if (request != null && request.website() != null && !request.website().isBlank()) {
                /* The same success a real order gets, without an id: the page offers no change link. */
                return receipt(201, new OrderReceipt(null,
                        "WEB-" + UUID.randomUUID().toString().replace("-", "")
                                .substring(0, 20).toUpperCase(Locale.ROOT),
                        null, "RECEIVED"));
            }
            orders.validatePlace(request, session);
            /* The page's own quote form token and widget are reused; ACCOUNT_ORDER only keeps
               the stored answers of orders apart from those of quote requests. */
            security.verifySubmission(PublicFormPurpose.QUOTE,
                    request == null ? null : request.formToken(),
                    request == null ? null : request.challengeToken());
            return receipt(201, idempotency.executeAccepted(PublicFormPurpose.ACCOUNT_ORDER, idempotencyKey,
                    fingerprint, PublicFormAction.ACCOUNT_QUOTE_SUBMIT, session.email(), OrderReceipt.class,
                    () -> orders.placeByCustomer(request, session)));
        } catch (WebOrderRefusal refusal) {
            return refused(refusal);
        } catch (PublicFormValidationException | PublicFormRateLimitException
                 | PublicFormServiceUnavailableException expected) {
            throw expected;
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "The order of customer %d could not be placed", session.customerId());
            return reviewRequired();
        }
    }

    @POST
    @Path("/{id:\\d{1,18}}/changes")
    @Consumes(MediaType.APPLICATION_JSON)
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = OrderRequest.class)
    public Response change(@PathParam("id") long id, OrderRequest request,
                           @HeaderParam("Idempotency-Key") String idempotencyKey) {
        CustomerSession session = guard.require(httpRequest);
        if (!documents.orderingEnabled()) return orderingOff();
        String fingerprint = fingerprint(session.accountId(), "CHANGE", id,
                request == null ? null : request.baseRevision(), request);
        try {
            var replay = idempotency.replay(PublicFormPurpose.ACCOUNT_ORDER, idempotencyKey,
                    fingerprint, OrderReceipt.class);
            if (replay.isPresent()) return receipt(200, replay.get());
            countWrite(session);
            /* No pretended success on a change: the page would show an order that never changed. */
            if (request != null && request.website() != null && !request.website().isBlank()) {
                throw new PublicFormValidationException(Map.of("request", "INVALID"));
            }
            /* Whose order and whether it is still open come first, without a lock; the write asks again under it. */
            orders.validateChange(id, request, session);
            security.verifySubmission(PublicFormPurpose.QUOTE,
                    request == null ? null : request.formToken(),
                    request == null ? null : request.challengeToken());
            return receipt(200, idempotency.execute(PublicFormPurpose.ACCOUNT_ORDER, idempotencyKey,
                    fingerprint, OrderReceipt.class, () -> orders.changeByCustomer(id, request, session)));
        } catch (WebOrderRefusal refusal) {
            return refused(refusal);
        } catch (PublicFormValidationException | PublicFormRateLimitException
                 | PublicFormServiceUnavailableException expected) {
            throw expected;
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Order %d could not be changed by its customer", id);
            return reviewRequired();
        }
    }

    /**
     * No form token here: the bearer is the credential, and the call is budgeted, replayed by
     * its key and checked against the state of the order under the lock.
     */
    @POST
    @Path("/{id:\\d{1,18}}/cancellation")
    @Consumes(MediaType.APPLICATION_JSON)
    @PublicFormBodyLimited(maxBytes = MAX_BODY_BYTES, body = CancelRequest.class)
    public Response cancel(@PathParam("id") long id, CancelRequest request,
                           @HeaderParam("Idempotency-Key") String idempotencyKey) {
        CustomerSession session = guard.require(httpRequest);
        if (!documents.orderingEnabled()) return orderingOff();
        Integer baseRevision = request == null ? null : request.baseRevision();
        String fingerprint = fingerprint(session.accountId(), "CANCEL", id, baseRevision, null);
        try {
            var replay = idempotency.replay(PublicFormPurpose.ACCOUNT_ORDER, idempotencyKey,
                    fingerprint, OrderReceipt.class);
            if (replay.isPresent()) return receipt(200, replay.get());
            countWrite(session);
            if (baseRevision == null) throw new PublicFormValidationException(Map.of("baseRevision", "REQUIRED"));
            return receipt(200, idempotency.execute(PublicFormPurpose.ACCOUNT_ORDER, idempotencyKey,
                    fingerprint, OrderReceipt.class, () -> orders.cancelByCustomer(id, baseRevision, session)));
        } catch (WebOrderRefusal refusal) {
            return refused(refusal);
        } catch (PublicFormValidationException | PublicFormRateLimitException
                 | PublicFormServiceUnavailableException expected) {
            throw expected;
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Order %d could not be cancelled by its customer", id);
            return reviewRequired();
        }
    }

    /** Change and cancel share one budget per network and one per login. */
    private void countWrite(CustomerSession session) {
        rateLimiter.checkIp(PublicFormAction.ACCOUNT_ORDER_WRITE, identities.resolve(httpRequest));
        rateLimiter.checkKey(PublicFormAction.ACCOUNT_ORDER_WRITE, "ACCOUNT",
                String.valueOf(session.accountId()), WRITES_PER_ACCOUNT);
    }

    private String fingerprint(long accountId, String op, Long orderId, Integer baseRevision,
                               OrderRequest request) {
        try {
            return json.writeValueAsString(request == null
                    ? new OrderFingerprint(accountId, op, orderId, baseRevision, null, null, null, null,
                            null, null, null, null, null, null)
                    : new OrderFingerprint(accountId, op, orderId, baseRevision, request.language(),
                            request.fulfillment(), request.destination(), request.items(),
                            request.contactName(), request.phone(), request.notes(),
                            request.privacyAccepted(), request.website(), request.pickupLocationId()));
        } catch (Exception exception) {
            throw new IllegalStateException("Order request could not be fingerprinted", exception);
        }
    }

    private static Response refused(WebOrderRefusal refusal) {
        return switch (refusal.code()) {
            case NOT_FOUND -> error(404, "ORDER_NOT_FOUND", "Not found", Map.of());
            case LOCKED -> error(409, "ORDER_LOCKED", "The order can no longer be changed", Map.of());
            case CHANGED -> error(409, "ORDER_CHANGED", "The order was changed in the meantime",
                    Map.of("baseRevision", "STALE"));
            case CHANGE_LIMIT -> error(409, "ORDER_CHANGE_LIMIT", "The order cannot be changed again", Map.of());
        };
    }

    /** What an older backend answers too: the website then offers the quote request. */
    private static Response orderingOff() {
        return error(404, "NOT_FOUND", "Not found", Map.of());
    }

    /** Internal catalogue and order details do not belong in a customer response. */
    private static Response reviewRequired() {
        return error(409, "ORDER_REVIEW_REQUIRED", "The order could not be completed automatically", Map.of());
    }

    private static Response receipt(int status, OrderReceipt receipt) {
        return noStore(Response.status(status).entity(receipt));
    }

    private static Response error(int status, String code, String message, Map<String, String> fieldErrors) {
        return noStore(Response.status(status).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new PublicQuoteDtos.ErrorResponse(code, message, fieldErrors)));
    }

    private static Response noStore(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store").build();
    }

    /**
     * The form token and the challenge are left out on purpose: a retry with a fresh token is
     * the same order. The login is in it, so a key is never answered to another login.
     */
    private record OrderFingerprint(
            long accountId, String op, Long orderId, Integer baseRevision, String language,
            String fulfillment, PublicQuoteDtos.Destination destination,
            List<PublicQuoteDtos.ItemRequest> items, String contactName, String phone, String notes,
            Boolean privacyAccepted, String website, Long pickupLocationId) {}
}
