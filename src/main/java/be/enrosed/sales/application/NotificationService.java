package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Wat er op ons ligt te wachten.
 *
 * Twee soorten meldingen, en het onderscheid is belangrijker dan het lijkt:
 *  - **wij zijn aan zet**: een klant wacht op iets van ons (een levertermijn,
 *    een vrachtbedrag, een voorstel dat beoordeeld moet worden)
 *  - **de klant heeft iets gedaan**: getekend, afgewezen, of gewoon gekeken
 *
 * Het eerste soort is werk, het tweede is nieuws. Ze door elkaar tonen maakt
 * dat je het werk mist tussen de meldingen dat er weer iemand gekeken heeft.
 *
 * Bewust berekend uit de orders en niet in een aparte tabel bijgehouden: er is
 * niets om bij te houden dat niet al in de orderstatus staat, en een tweede
 * plaats waar dezelfde waarheid staat gaat vroeg of laat uit elkaar lopen.
 */
@ApplicationScoped
public class NotificationService {

    private final SalesRepositories.Orders orders;
    private final SalesRepositories.Revisions revisions;
    private final CustomerService customers;

    public NotificationService(SalesRepositories.Orders orders,
                               SalesRepositories.Revisions revisions,
                               CustomerService customers) {
        this.orders = orders;
        this.revisions = revisions;
        this.customers = customers;
    }

    /** Wat voor melding het is; stuurt het icoon en de kleur in het scherm. */
    public enum Kind {
        /** Een klant wacht op een levertermijn van ons. */
        LEVERTERMIJN,
        /** Een klant wacht op een vrachtbedrag van ons. */
        VRACHT,
        /** Een wijzigingsvoorstel ligt ter beoordeling. */
        VOORSTEL,
        /** De klant heeft getekend. */
        GETEKEND,
        /** De klant heeft afgewezen. */
        AFGEWEZEN,
        /** De klant heeft de offerte bekeken. */
        BEKEKEN
    }

    public record Notification(
            Kind kind,
            Long orderId,
            String orderNumber,
            String customer,
            String title,
            String detail,
            /** Moeten wij iets doen, of is dit alleen nieuws? */
            boolean actionNeeded,
            Instant at
    ) {}

    public record Feed(List<Notification> items, int actionCount) {}

    public Feed feed() {
        List<Notification> items = new ArrayList<>();

        for (SalesOrder order : orders.findAll()) {
            String who = customerName(order);

            /* ---- wij zijn aan zet ---------------------------------------- */

            if (order.status().isOpenForCustomer()
                    && order.deliveryTerms() == DeliveryTermsState.TE_BEPALEN) {
                items.add(new Notification(Kind.LEVERTERMIJN, order.id(), order.number(), who,
                        "Levertermijn nog te bepalen",
                        "De klant wacht op een leverdatum. Vul de leverweek in en stuur opnieuw.",
                        true, order.sentAt()));
            }

            if (order.status().isOpenForCustomer() && order.freight() == FreightState.TE_BEPALEN) {
                items.add(new Notification(Kind.VRACHT, order.id(), order.number(), who,
                        "Vracht nog te bepalen",
                        "De offerte vertrok zonder vrachtbedrag. Vul het in en stuur opnieuw.",
                        true, order.sentAt()));
            }

            /* ---- nieuws van de klant ------------------------------------- */

            if (order.status() == QuoteStatus.GEACCEPTEERD && order.decidedAt() != null) {
                items.add(new Notification(Kind.GETEKEND, order.id(), order.number(), who,
                        "Offerte getekend",
                        order.signedByName() == null
                                ? "De klant heeft aanvaard." : "Getekend door " + order.signedByName(),
                        false, order.decidedAt()));
            }

            if (order.status() == QuoteStatus.AFGEWEZEN && order.decidedAt() != null) {
                items.add(new Notification(Kind.AFGEWEZEN, order.id(), order.number(), who,
                        "Offerte afgewezen",
                        order.customerMessage() == null || order.customerMessage().isBlank()
                                ? "Zonder opgave van reden. Je kan ze heropenen en bijsturen."
                                : order.customerMessage(),
                        false, order.decidedAt()));
            }

            if (order.status() == QuoteStatus.BEKEKEN && order.viewedAt() != null) {
                items.add(new Notification(Kind.BEKEKEN, order.id(), order.number(), who,
                        "Offerte bekeken",
                        order.viewCount() + "× geopend, nog geen antwoord.",
                        false, order.viewedAt()));
            }
        }

        /* Voorstellen die op beoordeling wachten: die staan los van de orderstatus. */
        revisions.findPending().forEach(revision -> {
            SalesOrder order = orders.findById(revision.salesOrderId()).orElse(null);
            if (order == null) return;
            items.add(new Notification(Kind.VOORSTEL, order.id(), order.number(),
                    customerName(order),
                    "Wijziging voorgesteld",
                    revision.proposedBy() == null
                            ? "De klant stelt andere aantallen voor."
                            : revision.proposedBy() + " stelt een wijziging voor.",
                    true, revision.proposedAt()));
        });

        /* Nieuwste eerst; meldingen zonder tijdstip achteraan in plaats van
           bovenaan - een null hoort niet als "zopas" te lezen. */
        items.sort(Comparator.comparing(Notification::at,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int actions = (int) items.stream().filter(Notification::actionNeeded).count();
        return new Feed(items, actions);
    }

    private String customerName(SalesOrder order) {
        if (order.customerId() == null) return null;
        try {
            return customers.get(order.customerId()).company();
        } catch (RuntimeException e) {
            /* Klant intussen verwijderd: dan is de melding nog steeds bruikbaar,
               alleen zonder naam erbij. */
            return null;
        }
    }
}
