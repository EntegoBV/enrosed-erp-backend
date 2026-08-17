package be.enrosed.sales.application.port.out;

import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;

import java.util.List;

/** Uitgaande poort die de offerte naar de klant stuurt. */
public interface QuoteMailer {

    /** Levertermijn per regel, zoals hij in de mail komt te staan. */
    record DeliveryLine(String description, String term, boolean known) {}

    /**
     * Waarom deze mail vertrekt, in plaats van een rij losse ja-neevlaggen.
     *
     * De klant moet bovenaan lezen wat er veranderd is: een tweede zending die
     * er hetzelfde uitziet als de eerste leest als een dubbele mail, en dan
     * kijkt niemand meer of er iets bij staat.
     */
    record Notice(boolean deliveryTermsAdded, boolean freightPending, boolean freightAdded) {

        public static Notice none() {
            return new Notice(false, false, false);
        }

        /** Is er iets bijzonders te melden, of is dit een gewone offerte? */
        public boolean isPlain() {
            return !deliveryTermsAdded && !freightPending && !freightAdded;
        }
    }

    void sendQuote(SalesOrder order, Customer customer, String portalUrl,
                   QuoteDocumentRenderer.Document document, String personalMessage,
                   List<DeliveryLine> deliveryLines, Notice notice);

    /** Bericht aan onszelf wanneer de klant iets doet met de offerte. */
    void notifyInternal(String subject, String body);
}
