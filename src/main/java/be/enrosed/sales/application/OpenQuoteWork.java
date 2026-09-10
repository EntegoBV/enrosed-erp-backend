package be.enrosed.sales.application;

import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.QuoteStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps the notification feed and the pending-proposal list on the same working documents. */
final class OpenQuoteWork {
    private OpenQuoteWork() {}

    static Set<Long> closedQuoteIds(List<SalesOrder> orders) {
        Set<Long> closed = new HashSet<>();
        for (SalesOrder order : orders) {
            if (order.isArchived() && order.id() != null) closed.add(order.id());
            // A term invoice is only part of an advance agreement; it does not close that quote.
            if (order.isInvoice() && order.status() != QuoteStatus.GEANNULEERD
                    && !order.isPartnerAdvance() && order.sourceQuoteId() != null) {
                closed.add(order.sourceQuoteId());
            }
        }
        return closed;
    }
}
