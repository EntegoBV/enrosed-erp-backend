package be.enrosed.sales.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Which customers a partner added itself in the partner portal (a buyer of a direct sale), implemented in
 * be.enrosed.partnerportal so sales does not depend on the portal. The customer list and sheet show the
 * origin; while staff have not marked it reviewed and the customer has no VAT number yet, a staff save does
 * not require one (staff fix the data first).
 */
public interface CustomerPartnerOrigins {

    /** "Aangemaakt door Frans via Partnerportaal · 12 nov 2026": the partner, who, when, and whether staff reviewed it. */
    record Origin(String partnerName, String createdByName, Instant createdAt, Instant reviewedAt) {}

    /** The origins of these customers (null = of every customer); a customer no partner added has none. */
    Map<Long, Origin> origins(Collection<Long> customerIds);

    /** A partner added this customer and staff have not marked it reviewed yet. */
    boolean awaitsReview(long customerId);
}
