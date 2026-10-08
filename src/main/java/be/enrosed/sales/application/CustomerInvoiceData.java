package be.enrosed.sales.application;

import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * The address data of a customer record an invoice cannot do without, in one
 * place: what is missing, how the refusal names it, and whether the delivery
 * address of the document may complete the record.
 *
 * A customer made when a website login is approved has a name and a VAT
 * number but no address; the address the customer typed for an order belongs
 * to that order. Staff may take it over into the record, and only into the
 * fields that are still empty.
 */
public final class CustomerInvoiceData {
    public static final String ADDRESS = "ADDRESS";
    public static final String POSTAL_CODE = "POSTAL_CODE";
    public static final String CITY = "CITY";

    /** The order is collected: its row names our warehouse, not the customer. */
    public static final String BLOCKED_PICKUP = "PICKUP";
    /** The document has no delivery address of this customer. */
    public static final String BLOCKED_NO_DELIVERY = "NO_DELIVERY";
    /** The record names another country than the one the order is delivered in. */
    public static final String BLOCKED_OTHER_COUNTRY = "OTHER_COUNTRY";
    /** The delivery address lacks a missing field, or contradicts a field the record already has. */
    public static final String BLOCKED_INCOMPLETE = "INCOMPLETE";

    /**
     * The address as the record will read after the takeover: a field the
     * record already has is shown in the record's own spelling, since it is
     * not written. The country is the record's own and null when it has
     * none: the takeover never writes a country, the delivery row has none.
     */
    public record Takeover(String address, String postalCode, String city, String countryCode) {}

    /** Either the address that may be taken over, or why it may not. */
    public record Offer(Takeover takeover, String blockedBy) {}

    /** What staff see on a document whose customer cannot be invoiced yet. */
    public record Notice(Long customerId, String company, List<String> missing, Takeover takeover,
                         String takeoverBlockedBy) {}

    private CustomerInvoiceData() {}

    /** The codes of the empty fields, always in the order street, postal code, city. */
    public static List<String> missing(Customer customer) {
        List<String> missing = new ArrayList<>(3);
        if (blank(customer.address())) missing.add(ADDRESS);
        if (blank(customer.postalCode())) missing.add(POSTAL_CODE);
        if (blank(customer.city())) missing.add(CITY);
        return List.copyOf(missing);
    }

    /** "straat en nummer, postcode en stad". */
    public static String missingText(List<String> missing) {
        List<String> words = missing.stream().map(code -> switch (code) {
            case ADDRESS -> "straat en nummer";
            case POSTAL_CODE -> "postcode";
            case CITY -> "stad";
            default -> code;
        }).toList();
        if (words.size() < 2) return String.join("", words);
        return String.join(", ", words.subList(0, words.size() - 1)) + " en " + words.getLast();
    }

    /** The sentence staff read when the document cannot be issued; null when nothing is missing. */
    public static String refusal(Customer customer, boolean creditNote) {
        return refusal(customer, creditNote, false);
    }

    /**
     * The same sentence for a document that may be issued already: the
     * address of the record can be emptied afterwards, and then it is the
     * sending that is refused, not the issuing.
     */
    public static String refusal(Customer customer, boolean creditNote, boolean alreadyIssued) {
        List<String> missing = missing(customer);
        if (missing.isEmpty()) return null;
        return (creditNote ? "De creditnota" : "De factuur") + " kan niet "
                + (alreadyIssued ? "verstuurd" : "uitgereikt") + " worden: bij klant "
                + customer.company() + (missing.size() == 1 ? " ontbreekt " : " ontbreken ") + missingText(missing)
                + ". Vul dit in bij de klantgegevens.";
    }

    /**
     * Whether the notice belongs on this document: one that can still lead
     * to an invoice. A document that is closed, withdrawn or already issued
     * asks nothing of staff any more.
     */
    public static boolean appliesTo(SalesOrder order) {
        if (order == null || order.id() == null || order.customerId() == null || order.archivedAt() != null) return false;
        QuoteStatus status = order.status();
        if (status == QuoteStatus.GEANNULEERD || status == QuoteStatus.AFGEWEZEN || status == QuoteStatus.VERLOPEN) return false;
        return !order.isClaimDocument() || status == QuoteStatus.CONCEPT;
    }

    /**
     * The takeover is offered only when it completes the record with one
     * address: every empty field has a value on the delivery, and every field
     * the record already has says the same as the delivery. Anything else is
     * for staff to type in the record themselves.
     */
    public static Offer offer(Customer customer, SalesOrder order, WebOrderDeliveries.Delivery delivery) {
        if (delivery == null) return new Offer(null, BLOCKED_NO_DELIVERY);
        if (!WebOrderDeliveries.DELIVERY.equals(delivery.fulfillment())) return new Offer(null, BLOCKED_PICKUP);
        String orderCountry = order == null ? null : order.countryCode();
        if (!blank(customer.countryCode()) && !blank(orderCountry) && !sameText(customer.countryCode(), orderCountry))
            return new Offer(null, BLOCKED_OTHER_COUNTRY);
        if (!fits(customer.address(), delivery.address()) || !fits(customer.postalCode(), delivery.postalCode())
                || !fits(customer.city(), delivery.city()))
            return new Offer(null, BLOCKED_INCOMPLETE);
        /* The country of a document is staff's to change and no part of the delivery address: it is never taken over. */
        String country = blank(customer.countryCode()) ? null : customer.countryCode().strip();
        return new Offer(new Takeover(afterwards(customer.address(), delivery.address()),
                afterwards(customer.postalCode(), delivery.postalCode()), afterwards(customer.city(), delivery.city()),
                country), null);
    }

    /** What the record reads after the takeover: its own value where it has one, else the delivery's. */
    private static String afterwards(String recorded, String delivered) {
        return blank(recorded) ? delivered.strip() : recorded;
    }

    /** The notice of a document for this customer; null when the record is complete or the document asks nothing. */
    public static Notice notice(Customer customer, SalesOrder order, WebOrderDeliveries.Delivery delivery) {
        if (customer == null || !appliesTo(order)) return null;
        List<String> missing = missing(customer);
        if (missing.isEmpty()) return null;
        Offer offer = offer(customer, order, delivery);
        return new Notice(customer.id(), customer.company(), missing, offer.takeover(), offer.blockedBy());
    }

    /** Typed twice the same: spaces around it and capitals do not make two addresses. */
    public static boolean sameText(String left, String right) {
        return (left == null ? "" : left.strip()).equalsIgnoreCase(right == null ? "" : right.strip());
    }

    private static boolean fits(String recorded, String delivered) {
        if (blank(delivered)) return false;
        return blank(recorded) || sameText(recorded, delivered);
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
