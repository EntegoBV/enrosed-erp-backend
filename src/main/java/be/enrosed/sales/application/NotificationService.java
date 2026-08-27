package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What is waiting on us.
 *
 * Two kinds of notifications, and the distinction matters more than it seems:
 *  - **our move**: a customer is waiting on something from us (a delivery
 *    term, a freight amount, a proposal to review)
 *  - **the customer did something**: signed, rejected, or simply looked
 *
 * The first kind is work, the second is news. Mixing them means missing the
 * work between notifications that yet another person had a look.
 *
 * Deliberately computed from the orders rather than kept in a separate
 * table: there is nothing to keep that is not already in the order status,
 * and a second place holding the same truth drifts apart sooner or later.
 */
@ApplicationScoped
public class NotificationService {

    private final SalesRepositories.Orders orders;
    private final SalesRepositories.Revisions revisions;
    private final SalesRepositories.Events events;
    private final CustomerService customers;

    public NotificationService(SalesRepositories.Orders orders,
                               SalesRepositories.Revisions revisions,
                               SalesRepositories.Events events,
                               CustomerService customers) {
        this.orders = orders;
        this.revisions = revisions;
        this.events = events;
        this.customers = customers;
    }

    /** What kind of notification it is; drives the icon and colour on screen. */
    public enum Kind {
        /** A complete public website request awaits its first staff review. */
        WEBSITE_AANVRAAG,
        /** A customer is waiting on a delivery term from us. */
        LEVERTERMIJN,
        /** A customer is waiting on a freight amount from us. */
        VRACHT,
        /** A change proposal awaits review. */
        VOORSTEL,
        /** The customer signed. */
        GETEKEND,
        /** The customer rejected. */
        AFGEWEZEN,
        /** The customer viewed the quote. */
        BEKEKEN
    }

    public record Notification(
            Kind kind,
            Long orderId,
            String orderNumber,
            String customer,
            String title,
            String detail,
            /** Do we need to act, or is this just news? */
            boolean actionNeeded,
            Instant at
    ) {}

    public record Feed(List<Notification> items, int actionCount) {}

    public Feed feed() {
        List<Notification> items = new ArrayList<>();

        for (SalesOrder order : orders.findAll()) {
            String who = customerName(order);

            /* ---- our move ------------------------------------------------ */

            if (isWebsiteRequestAwaitingReview(order)) {
                items.add(new Notification(Kind.WEBSITE_AANVRAAG,
                        order.id(), order.number(), who,
                        "Nieuwe websiteaanvraag",
                        "Controleer aantallen, prijzen, btw en levering en stuur daarna de offerte.",
                        true, createdAt(order)));
            }

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

            /* ---- news from the customer ---------------------------------- */

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

        /* Proposals awaiting review: those live apart from the order status. */
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

        /* Newest first; notifications without a timestamp go to the back
           instead of the top - a null should not read as "just now". */
        items.sort(Comparator.comparing(Notification::at,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int actions = (int) items.stream().filter(Notification::actionNeeded).count();
        return new Feed(items, actions);
    }

    private static boolean isWebsiteRequestAwaitingReview(SalesOrder order) {
        return order != null && !order.isInvoice()
                && order.status() == QuoteStatus.CONCEPT
                && order.internalNotes() != null
                && order.internalNotes().stripLeading()
                        .startsWith(SalesOrderService.WEBSITE_REQUEST_MARKER);
    }

    private Instant createdAt(SalesOrder order) {
        if (order.id() == null) return null;
        return events.findByOrder(order.id()).stream()
                .filter(event -> event.type() == QuoteEvent.Type.OPGEMAAKT)
                .map(QuoteEvent::at)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private String customerName(SalesOrder order) {
        if (order.customerId() == null) return null;
        try {
            return customers.get(order.customerId()).company();
        } catch (RuntimeException e) {
            /* Customer deleted in the meantime: the notification is still
               useful, just without a name attached. */
            return null;
        }
    }
}
