package be.enrosed.sales.domain;

import java.time.Instant;

/**
 * One step in the life of a quote.
 *
 * An order's status says where it stands NOW, not how it got there. For a
 * quote that went back and forth three times, the second is exactly what you
 * need: what did the customer propose, what did we adopt, and when. Without
 * that trail an order cannot be explained a week later - not to the customer
 * and not to ourselves.
 *
 * Events are only appended, never changed or deleted. A withdrawn proposal
 * stays too: that it was withdrawn is itself a step in the story.
 */
public record QuoteEvent(
        Long id,
        Long salesOrderId,
        Type type,
        Instant at,
        /** Who did it: our username or the name the customer typed. */
        String actor,
        /** Whether it came from the customer side; drives how it shows on screen. */
        boolean byCustomer,
        /** Korte omschrijving in gewone taal. */
        String summary,
        /** Whatever else belongs to it, for instance the changed quantities. */
        String detail
) {

    public enum Type {
        OPGEMAAKT,
        VERSTUURD,
        BEKEKEN,
        VOORSTEL,
        VOORSTEL_INGETROKKEN,
        VOORSTEL_OVERGENOMEN,
        VOORSTEL_AFGEWEZEN,
        GETEKEND,
        AFGEWEZEN,
        HEROPEND,
        /** We withdrew the quote; the detail carries what we told the customer. */
        GEANNULEERD,
        LEVERTERMIJN_INGEVULD,
        VRACHT_INGEVULD,
        GEFACTUREERD,
        BESTELLING_VERZONDEN,
        BETAALD
    }
}
