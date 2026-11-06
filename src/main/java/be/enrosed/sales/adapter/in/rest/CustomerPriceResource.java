package be.enrosed.sales.adapter.in.rest;

import be.enrosed.sales.application.CustomerPriceService;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** The prices agreed with one customer (klantprijzen), saved on their own from the customer sheet. */
@Path("/api/customers/{id}/prices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class CustomerPriceResource {

    private final CustomerPriceService prices;

    public CustomerPriceResource(CustomerPriceService prices) {
        this.prices = prices;
    }

    /** The body of a save: {@code {source, changes: [...]}}. */
    public static final class ChangeBody {
        public String source;
        public List<ChangeLine> changes;
    }

    /**
     * One product of a save. Jackson calls a setter only for a property the body names, so
     * {@code validUntil} or {@code note} left out keeps what is stored, while an explicit null
     * clears it; the price is always the change's own (null removes the product's price).
     */
    public static final class ChangeLine {
        public Long productId;
        public BigDecimal priceExclVat;
        private LocalDate validUntil;
        private String note;
        private boolean validUntilSent;
        private boolean noteSent;

        public void setValidUntil(LocalDate value) {
            validUntil = value;
            validUntilSent = true;
        }

        public void setNote(String value) {
            note = value;
            noteSent = true;
        }

        CustomerPriceService.Change toChange() {
            return new CustomerPriceService.Change(productId, priceExclVat, validUntil, note, validUntilSent, noteSent);
        }
    }

    @GET
    public List<CustomerPriceService.Row> list(@PathParam("id") long id) {
        return prices.list(id);
    }

    /** Only the listed products change; a null price removes one. Answers the full list. */
    @PUT
    public List<CustomerPriceService.Row> change(@PathParam("id") long id, ChangeBody body) {
        List<CustomerPriceService.Change> changes = body == null || body.changes == null ? null
                : body.changes.stream().filter(Objects::nonNull).map(ChangeLine::toChange).toList();
        return prices.change(id, new CustomerPriceService.ChangeRequest(body == null ? null : body.source, changes));
    }

    @GET
    @Path("/history")
    public List<CustomerPriceService.HistoryRow> history(@PathParam("id") long id,
                                                         @QueryParam("productId") Long productId) {
        return prices.history(id, productId);
    }
}
