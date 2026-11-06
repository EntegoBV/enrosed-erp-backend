package be.enrosed.account;

import be.enrosed.account.CustomerSessionGuard.CustomerSession;
import be.enrosed.publicform.PublicFormAction;
import be.enrosed.publicform.PublicFormRateLimiter;
import be.enrosed.sales.application.PublicQuoteService;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The logged-in customer's own agreed prices (klantprijzen) of the products the website sells,
 * for the product pages: only products with an active agreed price, never the list price. The
 * session guard comes first, as on every account route; the customer comes from the session
 * alone. Not tied to the ordering switch. Its budget is per account and of its own, so browsing
 * product pages never eats the quote page's configuration budget, and there is no IP bucket
 * (IP buckets are shared site-wide behind the proxy).
 */
@Path("/api/v1/public/account/prices")
@PermitAll
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public class CustomerPriceListResource {
    private static final int READS_PER_ACCOUNT = 600;

    private final PublicQuoteService quotes;
    private final CustomerSessionGuard guard;
    private final PublicFormRateLimiter rateLimiter;

    @Context
    HttpServerRequest httpRequest;

    public CustomerPriceListResource(PublicQuoteService quotes, CustomerSessionGuard guard,
                                     PublicFormRateLimiter rateLimiter) {
        this.quotes = quotes;
        this.guard = guard;
        this.rateLimiter = rateLimiter;
    }

    @GET
    public Response prices() {
        CustomerSession session = guard.require(httpRequest);
        rateLimiter.checkKey(PublicFormAction.ACCOUNT_PRICE_READ, "ACCOUNT",
                String.valueOf(session.accountId()), READS_PER_ACCOUNT);
        /* A customer's own prices: never kept by a browser or anything between it and us. */
        return Response.ok(quotes.accountPrices(session.customerId()))
                .header("Cache-Control", "no-store, private").build();
    }
}
