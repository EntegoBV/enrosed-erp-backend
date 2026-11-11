package be.enrosed.sales.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tells a pallet height someone set by hand from one a document merely carries.
 *
 * From 2026-11-07 to 2026-11-10 the standard was 220 cm, so a document of those days stores 220
 * without anyone having picked it, while a seller may just as well have set 220 on purpose. The
 * difference is in the history. Every save that changes the height writes an activity row that
 * names the field (SalesOrderService.salesChanges), and since 2026-11-10 a Nieuwe kopie that keeps
 * a height other than the standard writes one too. A document without such a row may still have
 * inherited a pick: an invoice from its quote (sourceQuoteId), an older Nieuwe kopie from its
 * source (the "... gekopieerd vanuit <nummer>" step in its history). So the question goes up that
 * chain until a pick is found, an original is reached or the trail ends.
 *
 * Read with plain SQL, deleted documents included: PalletHeightReturn asks inside its own
 * transaction, and Nieuwe kopie asks for one document.
 */
@ApplicationScoped
public class PalletHeightPicks {

    public enum Pick {
        /** A logged change names the height, on the document or on what it was made or copied from. */
        PICKED,
        /** No pick on the whole chain: the height is the standard of the day the original was made. */
        NOT_PICKED,
        /** The chain cannot be followed (a source that is gone or was renumbered). */
        UNKNOWN
    }

    /** The field's name in an activity row's changes (SalesOrderService.salesChanges and duplicate). */
    public static final String FIELD = "maxPalletHeightCm";

    /** The value of :picked in OWN_PICK. */
    static final String PICKED_PATTERN = "%\"" + FIELD + "\"%";

    /**
     * SQL test on a sales_order row o: a logged change of the document itself names the height.
     * Bind :picked to PICKED_PATTERN. ownPick() asks the same for one id: keep the two alike.
     */
    static final String OWN_PICK = "exists (select 1 from activity_log a where a.entity_type = 'SALES_ORDER'"
            + " and a.entity_id = cast(o.id as varchar) and a.changes_json like :picked)";

    /** A Nieuwe kopie's first history step: "Offerte" or "Factuur", this, the source's number. */
    static final String COPIED_FROM = " gekopieerd vanuit ";

    /** Longer chains than any real one; stops a loop in damaged data. */
    private static final int MAX_STEPS = 20;

    @Inject
    EntityManager entities;

    /** Whether the height the document stores was set by hand, on it or further up its chain. */
    public Pick of(long orderId) {
        Set<Long> seen = new HashSet<>();
        Long current = orderId;
        while (current != null && seen.size() < MAX_STEPS && seen.add(current)) {
            if (ownPick(current)) return Pick.PICKED;
            List<?> row = entities.createNativeQuery("select o.sourcequoteid from sales_order o where o.id = :id")
                    .setParameter("id", current).getResultList();
            if (row.isEmpty()) return Pick.UNKNOWN;
            if (row.get(0) != null) {
                /* An invoice, a slotfactuur: made with its quote's height. */
                current = ((Number) row.get(0)).longValue();
                continue;
            }
            String number = copiedFrom(current);
            if (number == null) return Pick.NOT_PICKED;
            current = idOf(number);
        }
        return Pick.UNKNOWN;
    }

    private boolean ownPick(long orderId) {
        Number rows = (Number) entities.createNativeQuery("select count(*) from activity_log a"
                        + " where a.entity_type = 'SALES_ORDER' and a.entity_id = :id and a.changes_json like :picked")
                .setParameter("id", Long.toString(orderId)).setParameter("picked", PICKED_PATTERN).getSingleResult();
        return rows.intValue() > 0;
    }

    /** The number of the document this one is a Nieuwe kopie of; null when it is no copy. */
    private String copiedFrom(long orderId) {
        List<?> steps = entities.createNativeQuery("select e.summary from quote_event e"
                        + " where e.salesorderid = :id and e.type = 'OPGEMAAKT' and e.summary like :copied order by e.id")
                .setParameter("id", orderId).setParameter("copied", "%" + COPIED_FROM + "%").getResultList();
        if (steps.isEmpty() || steps.get(0) == null) return null;
        String summary = steps.get(0).toString();
        return summary.substring(summary.indexOf(COPIED_FROM) + COPIED_FROM.length()).strip();
    }

    private Long idOf(String number) {
        List<?> ids = entities.createNativeQuery("select o.id from sales_order o where o.number = :number")
                .setParameter("number", number).getResultList();
        return ids.size() == 1 ? ((Number) ids.get(0)).longValue() : null;
    }
}
