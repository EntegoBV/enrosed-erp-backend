package be.enrosed.sales.domain;

import java.time.Instant;
import java.util.List;

/**
 * Wijzigingsvoorstel van de klant.
 *
 * De klant past de offerte niet rechtstreeks aan - dat zou betekenen dat een
 * verzonden document onder onze handen verandert. In plaats daarvan legt hij
 * een voorstel neer dat wij goedkeuren, aanpassen of afwijzen. Pas bij
 * goedkeuring gaan de regels over naar de order.
 */
public record QuoteRevision(
        Long id,
        Long salesOrderId,
        RevisionStatus status,
        Instant proposedAt,
        String proposedBy,
        String message,
        Instant handledAt,
        String handledBy,
        String responseMessage,
        List<Line> lines
) {
    public record Line(Long id, Long productId, int quantity, String note) {}

    public List<Line> lines() {
        return lines == null ? List.of() : lines;
    }
}
