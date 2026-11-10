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

    public static final class Rule {
        private final Map<Long, PartnerPortalQueries.DocumentState> documents;

        Rule(Map<Long, PartnerPortalQueries.DocumentState> documents) {
            this.documents = documents;
        }

        public boolean isOpen(PartnerSaleReportLineEntity line, boolean reportVoided) {
            if (reportVoided) return false;
            if (line.invoiceId == null) return true;
            PartnerPortalQueries.DocumentState document = documents.get(line.invoiceId);
            return document == null || RELEASING.contains(document.status());
        }

        /** The document that holds this part, or null when the part is open or the document is gone. */
        public PartnerPortalQueries.DocumentState holder(PartnerSaleReportLineEntity line) {
            if (line.invoiceId == null) return null;
            PartnerPortalQueries.DocumentState document = documents.get(line.invoiceId);
            return document == null || RELEASING.contains(document.status()) ? null : document;
        }
    }
}
