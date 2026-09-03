package be.enrosed.sales.application.port.out;

import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;

import java.util.List;

/** Outbound port sending the quote to the customer. */
public interface QuoteMailer {

    /** Per-line delivery term, as it will appear in the mail. */
    record DeliveryLine(String description, String term, boolean known) {}

    /**
     * Why this mail leaves, instead of a row of loose yes/no flags.
     *
     * The customer must read at the top what changed: a second sending that
     * looks identical to the first reads as a duplicate mail, and then
     * nobody checks whether anything was added.
     */
    record Notice(boolean deliveryTermsAdded, boolean freightPending, boolean freightAdded) {

        public static Notice none() {
            return new Notice(false, false, false);
        }

        /** Anything special to report, or is this a plain quote? */
        public boolean isPlain() {
            return !deliveryTermsAdded && !freightPending && !freightAdded;
        }
    }

    void sendQuote(SalesOrder order, Customer customer, String portalUrl,
                   QuoteDocumentRenderer.Document document, String personalMessage,
                   List<DeliveryLine> deliveryLines, Notice notice);

    /** The invoice by mail: PDF attached, payment sentence in the body, no portal. */
    void sendInvoice(SalesOrder order, Customer customer,
                     QuoteDocumentRenderer.Document document, String personalMessage,
                     String paymentSentence);

    /** Tells the customer the quote is withdrawn; the portal link shows the same. */
    void sendCancellation(SalesOrder order, Customer customer, String portalUrl, String message);

    /** Message to ourselves when the customer acts on the quote. */
    void notifyInternal(String subject, String body);
}
