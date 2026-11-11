package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.QuoteEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tells a pallet height someone set by hand from one a document merely carries.
 *
 * From 2026-11-07 to 2026-11-10 the standard was 220 cm, so a document of those days stores 220
 * without anyone having picked it, while a seller may just as well have set 220 on purpose. The
 * difference is in the history. Every save that changes the height writes an activity row that
 * names the field with its old and new value (SalesOrderService.salesChanges), and since 2026-11-10
 * a Nieuwe kopie that keeps a height other than the standard writes one too.
 *
 * The height a document stores was set by hand when the latest of those changes ends at that very
 * height. A row by itself proves nothing: before 2026-11-07 "Standaard" emptied the height, and the
 * move of that day then wrote 220 on such a concept quote without a row. Its latest change ends at
 * nothing, so its 220 is nobody's choice.
 *
 * A document without a logged change may have inherited a pick: an invoice from its quote
 * (sourceQuoteId), an older Nieuwe kopie from its source (the "... gekopieerd vanuit <nummer>" step
 * in its history). It was made with what the source held at that moment, so of the source only the
 * changes up to the moment the document was made count: what happened to the source afterwards says
 * nothing about the copy. With no change before that moment the question goes further up, until a
 * change is found, an original is reached or the trail ends.
 *
 * Deleted documents are read too: PalletHeightReturn asks inside its own transaction, and Nieuwe
 * kopie asks for one document.
 */
@ApplicationScoped
public class PalletHeightPicks {

    public enum Pick {
        /** The latest logged change of the height on the document itself ends at the height it stores. */
        SET_HERE,
        /** No change on the document; what it was made or copied from held a height set by hand at that moment. */
        INHERITED,
        /** Nobody set it: the height is the standard of the day the original was made, or the move of 2026-11-07. */
        NOT_PICKED,
        /** The chain cannot be followed (a source that is gone or was renumbered, a log row that cannot be read). */
        UNKNOWN;

        /** Set by hand, here or further up. */
        public boolean byHand() {
            return this == SET_HERE || this == INHERITED;
        }
    }

    /** The field's name in an activity row's changes (SalesOrderService.salesChanges and duplicate). */
    public static final String FIELD = "maxPalletHeightCm";

    /** A Nieuwe kopie's first history step: "Offerte" or "Factuur", this, the source's number. */
    static final String COPIED_FROM = " gekopieerd vanuit ";

    /** Longer chains than any real one; stops a loop in damaged data. */
    private static final int MAX_STEPS = 20;

    /** Where the latest logged change of the height ends, compared with the height in question. */
    private enum End { NONE, AT_HEIGHT, ELSEWHERE, UNREADABLE }

    @Inject
    EntityManager entities;

    @Inject
    ObjectMapper json;

    @Inject
    SalesRepositories.Events events;

    /** Whether the height the document stores was set by hand, on it or further up its chain. */
    public Pick of(long orderId) {
        Object[] document = document(orderId);
        if (document == null) return Pick.UNKNOWN;
        BigDecimal height = (BigDecimal) document[0];
        /* No height of its own: made before 2026-11-07, it reads the 260 cm of that time. */
        if (height == null) return Pick.NOT_PICKED;
        /* The document's own log decides when it names the height at all: its height is then what its own
           changes (or the move of 2026-11-07 after them) left, whatever it was made from. */
        switch (lastChange(orderId, null, height)) {
            case AT_HEIGHT: return Pick.SET_HERE;
            case ELSEWHERE: return Pick.NOT_PICKED;
            case UNREADABLE: return Pick.UNKNOWN;
            default: break;
        }
        Set<Long> seen = new HashSet<>();
        long child = orderId;
        Object sourceQuote = document[1];
        while (seen.add(child) && seen.size() <= MAX_STEPS) {
            List<QuoteEvent> made = madeSteps(child);
            long source;
            Instant madeAt;
            if (sourceQuote != null) {
                /* An invoice, a slotfactuur: made with its quote's height. */
                source = ((Number) sourceQuote).longValue();
                madeAt = made.isEmpty() ? null : made.get(0).at();
            } else {
                QuoteEvent copied = made.stream().filter(step -> step.summary() != null && step.summary().contains(COPIED_FROM))
                        .findFirst().orElse(null);
                if (copied == null) return Pick.NOT_PICKED;
                String summary = copied.summary();
                Long id = idOf(summary.substring(summary.indexOf(COPIED_FROM) + COPIED_FROM.length()).strip());
                if (id == null) return Pick.UNKNOWN;
                source = id;
                madeAt = copied.at();
            }
            Object[] origin = document(source);
            if (origin == null) return Pick.UNKNOWN;
            switch (lastChange(source, madeAt, height)) {
                case AT_HEIGHT: return Pick.INHERITED;
                case ELSEWHERE: return Pick.NOT_PICKED;
                case UNREADABLE: return Pick.UNKNOWN;
                default: break;
            }
            child = source;
            sourceQuote = origin[1];
        }
        return Pick.UNKNOWN;
    }

    /** The document's stored height and the quote it was made from; null when the document is gone. */
    private Object[] document(long orderId) {
        List<?> rows = entities.createNativeQuery("select o.maxpalletheightcm, o.sourcequoteid from sales_order o where o.id = :id")
                .setParameter("id", orderId).getResultList();
        return rows.isEmpty() ? null : (Object[]) rows.get(0);
    }

    /**
     * Where the document's latest logged change of the height ends, counting only the changes up to
     * {@code until} (null: all of them). Read through the entity, as the history steps are through their
     * port, so the two moments compared are the ones the application wrote, whatever the column types.
     */
    private End lastChange(long orderId, Instant until, BigDecimal height) {
        List<Object[]> rows = entities.createQuery("select a.occurredAt, a.changesJson from ActivityLogEntity a"
                        + " where a.entityType = :type and a.entityId = :id and a.changesJson like :field"
                        + " order by a.occurredAt desc, a.id desc", Object[].class)
                .setParameter("type", SalesOrderService.SALES_ORDER_ACTIVITY_TYPE).setParameter("id", Long.toString(orderId))
                .setParameter("field", "%\"" + FIELD + "\"%").getResultList();
        for (Object[] row : rows) {
            Instant at = (Instant) row[0];
            if (until != null && at != null && at.isAfter(until)) continue;
            try {
                for (JsonNode change : json.readTree((String) row[1])) {
                    if (!FIELD.equals(change.path("field").asText(null))) continue;
                    JsonNode after = change.path("afterValue");
                    return after.isTextual() && sameHeight(after.asText(), height) ? End.AT_HEIGHT : End.ELSEWHERE;
                }
            } catch (Exception unreadable) {
                return End.UNREADABLE;
            }
        }
        return End.NONE;
    }

    /** A logged value is the height as a reader sees it ("220"); the stored one may read 220.00. */
    private static boolean sameHeight(String logged, BigDecimal height) {
        try {
            return new BigDecimal(logged.strip()).compareTo(height) == 0;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** The steps that say how and when the document was made, oldest first. */
    private List<QuoteEvent> madeSteps(long orderId) {
        return events.findByOrder(orderId).stream().filter(step -> step.type() == QuoteEvent.Type.OPGEMAAKT)
                .sorted(Comparator.comparing(QuoteEvent::id)).toList();
    }

    private Long idOf(String number) {
        List<?> ids = entities.createNativeQuery("select o.id from sales_order o where o.number = :number")
                .setParameter("number", number).getResultList();
        return ids.size() == 1 ? ((Number) ids.get(0)).longValue() : null;
    }
}
