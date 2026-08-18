package be.enrosed.sales.domain;

import java.time.Instant;
import java.util.List;

/**
 * The customer's change proposal.
 *
 * The customer does not edit the quote directly - that would mean a sent
 * document changing under our hands. Instead they put down a proposal that
 * we approve, adjust or reject. Only on approval do the lines move onto
 * the order.
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
