package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalQueries;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.sales.domain.QuoteStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * THE open-part rule, the one place it is written down (capacity, notifications, summary,
 * analytics, preview, the staff page and the partner's own list all ask this class):
 *
 * a part is open when its melding is not voided and either it was never invoiced, or the
 * document it went on no longer counts: deleted, in the trash, or GEANNULEERD / AFGEWEZEN /
 * VERLOPEN. A concept invoice still holds its parts.
 */
@ApplicationScoped
public class PartnerOpenParts {

    /** Statuses after which a document no longer holds the parts it was made from. */
    public static final Set<QuoteStatus> RELEASING = Set.of(
            QuoteStatus.GEANNULEERD, QuoteStatus.AFGEWEZEN, QuoteStatus.VERLOPEN);

    private final PartnerPortalQueries queries;

    public PartnerOpenParts(PartnerPortalQueries queries) {
        this.queries = queries;
    }

    /** The rule bound to the documents these parts point at (one query for all of them). */
    public Rule forLines(Collection<PartnerSaleReportLineEntity> lines) {
        Set<Long> invoiceIds = lines.stream().map(line -> line.invoiceId).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return new Rule(queries.documentStates(invoiceIds));
    }

    /**
     * Among these invoice ids, the ones that no longer hold their parts (gone, trashed or in a
     * RELEASING status): a part pointing at one of them is open. The same rule as {@link Rule},
     * for the cheap aggregate reads (summary, bell) that select open parts in the query.
     */
    public Set<Long> releasing(Collection<Long> invoiceIds) {
        Set<Long> result = new java.util.HashSet<>();
        if (invoiceIds == null || invoiceIds.isEmpty()) return result;
        Map<Long, PartnerPortalQueries.DocumentState> documents = queries.documentStates(invoiceIds);
        for (Long id : invoiceIds) {
            if (id == null) continue;
            if (!holds(documents.get(id))) result.add(id);
        }
        return result;
    }

    /** Whether this document (null = deleted or in the trash) still holds the parts that point at it. */
    static boolean holds(PartnerPortalQueries.DocumentState document) {
        return document != null && !RELEASING.contains(document.status());
    }

    public static final class Rule {
        private final Map<Long, PartnerPortalQueries.DocumentState> documents;

        Rule(Map<Long, PartnerPortalQueries.DocumentState> documents) {
            this.documents = documents;
        }

        public boolean isOpen(PartnerSaleReportLineEntity line, boolean reportVoided) {
            if (reportVoided) return false;
            return line.invoiceId == null || !holds(documents.get(line.invoiceId));
        }

        /** The document that holds this part, or null when the part is open or the document is gone. */
        public PartnerPortalQueries.DocumentState holder(PartnerSaleReportLineEntity line) {
            if (line.invoiceId == null) return null;
            PartnerPortalQueries.DocumentState document = documents.get(line.invoiceId);
            return holds(document) ? document : null;
        }
    }
}
