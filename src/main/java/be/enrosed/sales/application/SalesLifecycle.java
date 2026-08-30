package be.enrosed.sales.application;

import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;

/**
 * The state boundaries around a quotation.
 *
 * Keeping these checks together matters because the ordinary edit endpoint,
 * the dedicated delivery/freight updates, sending and the public portal each
 * have a deliberately different boundary. A new endpoint should not have to
 * reinvent which states it may touch.
 */
final class SalesLifecycle {

    private SalesLifecycle() {}

    /** Full order edits are only safe while the quotation is a draft. */
    static void requireEditable(SalesOrder order) {
        if (order.status() == null) {
            throw new BusinessRuleException("Kies een geldige status voor de offerte");
        }
        if (order.status() != QuoteStatus.CONCEPT) {
            throw new BusinessRuleException(
                    "Offerte " + order.number() + " is al verstuurd en kan niet volledig gewijzigd worden. "
                            + "Heropen ze eerst via de daarvoor bedoelde flow.");
        }
    }

    /**
     * A draft may leave for the first time; an open quotation may be resent.
     * A pending customer proposal and every final state need an explicit
     * transition before another document may leave.
     */
    static void requireSendable(SalesOrder order) {
        QuoteStatus status = order.status();
        if (status == null) {
            throw new BusinessRuleException("Kies een geldige status voor de offerte");
        }
        if (status != QuoteStatus.CONCEPT
                && status != QuoteStatus.VERZONDEN
                && status != QuoteStatus.BEKEKEN) {
            throw new BusinessRuleException(
                    "Offerte " + order.number() + " staat op "
                            + status.name().toLowerCase()
                            + " en kan vanuit die status niet verstuurd worden");
        }
    }

    /** Delivery weeks and freight have their own narrow update flow. */
    static void requireTermsEditable(SalesOrder order) {
        QuoteStatus status = order.status();
        if (status == null) {
            throw new BusinessRuleException("Kies een geldige status voor de offerte");
        }
        if (status != QuoteStatus.CONCEPT
                && status != QuoteStatus.VERZONDEN
                && status != QuoteStatus.BEKEKEN) {
            throw new BusinessRuleException(
                    "Levertermijn of vracht kan niet gewijzigd worden terwijl offerte "
                            + order.number() + " op " + status.name().toLowerCase() + " staat");
        }
    }

    /** Only a draft that has never left the company may be removed. */
    static void requireDeletable(SalesOrder order, boolean hasRevisions) {
        boolean unusedDraft = order.status() == QuoteStatus.CONCEPT
                && order.portalToken() == null
                && order.sentAt() == null
                && order.viewedAt() == null
                && order.viewCount() == 0
                && order.decidedAt() == null
                && !hasRevisions;
        if (!unusedDraft) {
            String document = order.isInvoice() ? "conceptfactuur" : "conceptofferte";
            throw new BusinessRuleException(
                    "Alleen een " + document
                            + " die nog nooit verstuurd of gebruikt is kan verwijderd worden");
        }
    }

    /**
     * There is no immutable sent snapshot yet. While staff is editing a
     * reopened quotation, exposing the aggregate would therefore show the
     * customer an unsent draft. Fail closed until snapshots are introduced.
     */
    static void requirePortalVisible(SalesOrder order) {
        if (!portalVisible(order)) {
            throw new BusinessRuleException(
                    "Deze offerte wordt momenteel bijgewerkt. De nieuwe versie is pas zichtbaar "
                            + "nadat Enrosed ze opnieuw heeft verstuurd.");
        }
    }

    /** Shared predicate for public lookup and the admin copy-link capability. */
    static boolean portalVisible(SalesOrder order) {
        return order != null && order.status() != null && order.status() != QuoteStatus.CONCEPT;
    }
}
