package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesWebOrderEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.mail.CustomerOrderMailer;
import be.enrosed.shared.mail.CustomerOrderMailer.OrderMail;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * The two mails a customer gets about a website order: "received" once the
 * order is safely stored, "in processing" once somebody at Enrosed took it.
 *
 * A mail never rolls back or delays the order or the staff action: after the
 * commit the delivery is handed to an executor, so neither the customer's
 * call nor the staff action waits for the mail provider. The executor's
 * threads have neither a request context nor a transaction; every database
 * access of a delivery sits in a transaction this class opens itself. There
 * is no job and no outbox. The moment a mail is sent is claimed with one
 * conditional update before it leaves, so two callers can never send it
 * twice; a failure puts the moment back and leaves the reason on the order,
 * where staff can send it again. A delivery that never starts (the executor
 * refuses it, or the application stops first) claims nothing: the mail
 * stays due and staff are offered it after a minute, like a failed one.
 */
@ApplicationScoped
public class WebOrderMails {
    private static final Logger LOG = Logger.getLogger(WebOrderMails.class);
    static final String NOTHING_DUE = "Er staat geen e-mail voor de klant open.";
    /** A due mail younger than this is still on its way; staff are not shown it yet. */
    private static final long OVERDUE_SECONDS = 60;
    private static final int MAX_ERROR = 300;
    /** How long a failed claim is remembered for the answers that read it while it stood. */
    private static final long GIVEN_BACK_SECONDS = 600;

    public enum Kind { RECEIVED, PROCESSING }

    /** The mail that is due, and whether its trigger is old enough to tell staff about it. */
    public record Due(Kind kind, boolean overdue) {}

    private record Prepared(Kind kind, boolean again, OrderMail mail) {}

    /** A claimed moment whose mail is with the mail provider right now. */
    private record UnderWay(long orderId, Kind kind, Instant claimed) {}

    /** Tests set Runnable::run or a capturing one, through useExecutor. */
    Executor executor = ForkJoinPool.commonPool();

    /**
     * The claims this process is still sending. A claim is written before
     * the mail leaves, so until the provider answers the stored moment says
     * "sent" about a mail that may yet fail; shownSentAt keeps it from staff
     * for that long. Kept in memory: it covers the sends of this process,
     * which is where the answer to the staff action that caused them is built.
     */
    private final Set<UnderWay> underWay = ConcurrentHashMap.newKeySet();

    /**
     * The claims this process gave back because the mail failed, with the
     * moment they failed. An answer that read the order while the mail was
     * leaving still holds the claimed moment after the failure; without
     * this it would read as sent. A claimed moment belongs to one attempt,
     * so it never is the moment of a mail that did leave. Kept for a few
     * minutes, far longer than an answer takes to build.
     */
    private final Map<UnderWay, Instant> givenBack = new ConcurrentHashMap<>();

    @Inject WebOrders webOrders;
    @Inject WebOrderDeliveries deliveries;
    @Inject WebOrderRecipients recipients;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Customers customers;
    @Inject CustomerOrderMailer mailer;
    @Inject EntityManager entities;

    /* A change is told on the website only and a customer's own cancellation needs no mail: neither is observed. */

    void onPlaced(@Observes(during = TransactionPhase.AFTER_SUCCESS) WebOrderEvents.Placed placed) {
        afterCommit(placed.orderId(), placed.number());
    }

    void onTaken(@Observes(during = TransactionPhase.AFTER_SUCCESS) WebOrderEvents.Taken taken) {
        afterCommit(taken.orderId(), taken.number());
    }

    /** Reads nothing and sends nothing on the committing thread. */
    private void afterCommit(long orderId, String number) {
        try {
            executor.execute(() -> sendDue(orderId, number));
        } catch (RuntimeException refused) {
            /* Nothing was claimed: the mail is still due and staff can send it from the order. */
            LOG.errorf(refused, "Klantmail voor websitebestelling %s kon niet ingepland worden en staat nog open", number);
        }
    }

    /** Runs on the executor; whatever happens stays here, the commit that asked for it stands. */
    private void sendDue(long orderId, String number) {
        try {
            deliver(orderId, false);
        } catch (BusinessRuleException notSent) {
            // Logged where it failed and left on the order; the commit that fired this stands.
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Klantmail voor websitebestelling %s kon niet vertrekken", number);
        }
    }

    /**
     * Which mail is due, or null. Both need the document to exist, to be
     * still the placing customer's, an unsent concept, and not cancelled by
     * the customer. "Received" is due until it was sent or the order was
     * taken; "in processing" from the take until it was sent. So at most one
     * is due, and "you can still change it" is never sent for a taken order;
     * an order that the same staff action sent or cancelled gets neither.
     */
    public static Due due(SalesOrder order, WebOrders.Row row, Instant now) {
        if (!mailable(order, row)) return null;
        if (row.processingStartedAt() == null) {
            return row.receivedMailSentAt() != null ? null : new Due(Kind.RECEIVED, overdue(row.placedAt(), now));
        }
        return row.processingMailSentAt() != null ? null
                : new Due(Kind.PROCESSING, overdue(row.processingStartedAt(), now));
    }

    /**
     * Sends the mail that is due. With repeat, and nothing due, the last
     * order mail that is marked sent goes out once more: for a customer who
     * never got it, and for a mail whose moment was claimed just before the
     * process stopped.
     *
     * @throws BusinessRuleException when nothing is due or can be repeated, or when sending fails
     */
    public void resend(long orderId, boolean repeat) {
        boolean sent;
        try {
            sent = deliver(orderId, repeat);
        } finally {
            refreshHeldRow(orderId);
        }
        if (!sent) throw new BusinessRuleException(NOTHING_DUE);
    }

    /** False when no mail was due or somebody else is already sending it; a failed send throws its Dutch reason. */
    private boolean deliver(long orderId, boolean repeat) {
        Prepared prepared = QuarkusTransaction.requiringNew().call(() -> prepare(orderId, repeat));
        if (prepared == null) return false;
        Kind kind = prepared.kind();
        /* Microseconds, as the database keeps them: the release below finds its own claim by this value. */
        Instant claimed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        /* Noted before the claim is written and dropped after it is settled, so no reader sees one without the other. */
        UnderWay sending = new UnderWay(orderId, kind, claimed);
        if (!prepared.again()) underWay.add(sending);
        try {
            if (!prepared.again() && !claim(orderId, kind, claimed)) return false;
            try {
                send(kind, prepared.mail());
            } catch (RuntimeException failure) {
                String reason = reason(failure);
                LOG.errorf(failure, "Klantmail (%s) voor websitebestelling %s mislukt", kind, prepared.mail().number());
                /* A failed repeat writes nothing: the earlier mail keeps its moment. */
                if (!prepared.again()) {
                    /* Noted before the claim is given back and before the send stops being under way. */
                    noteGivenBack(sending);
                    release(orderId, kind, claimed, reason);
                }
                throw new BusinessRuleException(reason);
            }
            if (prepared.again()) {
                markRepeated(orderId, kind, Instant.now());
            } else {
                clearError(orderId);
            }
            return true;
        } finally {
            underWay.remove(sending);
        }
    }

    /**
     * The sent moment of a mail as staff may read it: the stored one, except
     * while it is only the claim of a send that is still under way, or of
     * one that failed after the caller read the order. Then there is none:
     * the order shows neither "sent" nor "not left" until the provider
     * answered, and a caller that read it during a send that then failed
     * shows the same until it reads the order again.
     */
    public Instant shownSentAt(long orderId, Kind kind, Instant stored) {
        if (stored == null) return null;
        UnderWay claim = new UnderWay(orderId, kind, stored);
        /* A failing send is noted as given back before it stops being under way, so one of the two always holds. */
        return underWay.contains(claim) || givenBack.containsKey(claim) ? null : stored;
    }

    private void noteGivenBack(UnderWay failed) {
        Instant now = Instant.now();
        givenBack.values().removeIf(at -> at.plusSeconds(GIVEN_BACK_SECONDS).isBefore(now));
        givenBack.put(failed, now);
    }

    /** A fresh read of the row and the document, in its own short transaction; null when there is nothing to send. */
    private Prepared prepare(long orderId, boolean repeat) {
        SalesOrder order = orders.findById(orderId).orElse(null);
        WebOrders.Row row = webOrders.find(orderId).orElse(null);
        Due due = due(order, row, Instant.now());
        Kind kind = due != null ? due.kind() : repeat ? repeatable(order, row) : null;
        if (kind == null) return null;
        Customer customer = customers.findById(order.customerId()).orElse(null);
        if (customer == null) return null;
        return new Prepared(kind, due == null, mail(order, row, customer));
    }

    /** The order mail that was marked sent last, for an order that could still get one. */
    private static Kind repeatable(SalesOrder order, WebOrders.Row row) {
        if (!mailable(order, row)) return null;
        if (row.processingStartedAt() == null) return row.receivedMailSentAt() == null ? null : Kind.RECEIVED;
        return row.processingMailSentAt() == null ? null : Kind.PROCESSING;
    }

    private static boolean mailable(SalesOrder order, WebOrders.Row row) {
        return order != null && row != null
                && row.customerId() != null && Objects.equals(row.customerId(), order.customerId())
                && order.status() == QuoteStatus.CONCEPT && order.sentAt() == null
                && row.customerCancelledAt() == null;
    }

    private static boolean overdue(Instant trigger, Instant now) {
        return trigger != null && now != null && trigger.plusSeconds(OVERDUE_SECONDS).isBefore(now);
    }

    /**
     * One statement in its own transaction; false when another caller owns
     * the mail. The row is never loaded and saved here, so a revision or
     * snapshot written at the same time cannot be overwritten.
     */
    boolean claim(long orderId, Kind kind, Instant now) {
        String statement = kind == Kind.RECEIVED
                ? "update SalesWebOrderEntity w set w.receivedMailSentAt = :now where w.salesOrderId = :id"
                        + " and w.receivedMailSentAt is null and w.processingStartedAt is null"
                : "update SalesWebOrderEntity w set w.processingMailSentAt = :now where w.salesOrderId = :id"
                        + " and w.processingMailSentAt is null and w.processingStartedAt is not null";
        return QuarkusTransaction.requiringNew().call(() -> entities.createQuery(statement)
                .setParameter("now", now).setParameter("id", orderId).executeUpdate()) == 1;
    }

    /**
     * The mail did not leave: it is due again, and staff read why on the
     * order. Only the claim of this attempt is given back. A repeat by staff
     * that succeeded while this send was hanging wrote its own moment, and
     * that mail stays sent; a "received" mail that fails after the order was
     * taken is no longer due, so it leaves no error behind either.
     */
    void release(long orderId, Kind kind, Instant claimed, String reason) {
        String statement = kind == Kind.RECEIVED
                ? "update SalesWebOrderEntity w set w.receivedMailSentAt = null, w.mailError = :error"
                        + " where w.salesOrderId = :id and w.receivedMailSentAt = :claimed and w.processingStartedAt is null"
                : "update SalesWebOrderEntity w set w.processingMailSentAt = null, w.mailError = :error"
                        + " where w.salesOrderId = :id and w.processingMailSentAt = :claimed";
        QuarkusTransaction.requiringNew().run(() -> entities.createQuery(statement)
                .setParameter("error", reason).setParameter("id", orderId).setParameter("claimed", claimed).executeUpdate());
    }

    /** Only one mail can be due, so an older error belongs to a mail that no longer is. */
    private void clearError(long orderId) {
        QuarkusTransaction.requiringNew().run(() -> entities.createQuery(
                        "update SalesWebOrderEntity w set w.mailError = null"
                                + " where w.salesOrderId = :id and w.mailError is not null")
                .setParameter("id", orderId).executeUpdate());
    }

    private void markRepeated(long orderId, Kind kind, Instant now) {
        String statement = "update SalesWebOrderEntity w set "
                + (kind == Kind.RECEIVED ? "w.receivedMailSentAt" : "w.processingMailSentAt")
                + " = :now, w.mailError = null where w.salesOrderId = :id";
        QuarkusTransaction.requiringNew().run(() -> entities.createQuery(statement)
                .setParameter("now", now).setParameter("id", orderId).executeUpdate());
    }

    private void send(Kind kind, OrderMail mail) {
        if (mail.to() == null || mail.to().isBlank())
            throw new BusinessRuleException("De klant heeft geen e-mailadres; de e-mail over de bestelling is niet verstuurd.");
        if (kind == Kind.RECEIVED) {
            mailer.sendOrderReceived(mail);
        } else {
            mailer.sendOrderInProcessing(mail);
        }
    }

    /** The mailer's own Dutch sentence; anything else gets one, since staff read this on the order. */
    private static String reason(RuntimeException failure) {
        String text = failure instanceof BusinessRuleException && failure.getMessage() != null
                && !failure.getMessage().isBlank()
                ? failure.getMessage() : "De e-mail aan de klant kon niet verzonden worden.";
        return text.length() <= MAX_ERROR ? text : text.substring(0, MAX_ERROR);
    }

    /**
     * The statements above went around the persistence context of the
     * caller; a row it already holds would still show the old moments.
     */
    private void refreshHeldRow(long orderId) {
        try {
            SalesWebOrderEntity held = entities.find(SalesWebOrderEntity.class, orderId);
            if (held != null) entities.refresh(held);
        } catch (RuntimeException outsideRequest) {
            // No persistence context to bring up to date.
        }
    }

    /** Lines, totals and country as the customer ordered them, and the address typed for this order; never the live document. */
    private OrderMail mail(SalesOrder order, WebOrders.Row row, Customer customer) {
        WebOrderRecipients.Recipient recipient = recipients.of(order, customer);
        WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
        WebOrderSnapshot.Totals totals = snapshot == null ? null : snapshot.totals();
        WebOrderDeliveries.Delivery delivery = deliveries.forDocument(order).orElse(null);
        Language language = recipient.language();

        List<CustomerOrderMailer.Line> lines = new ArrayList<>();
        if (snapshot != null) {
            for (WebOrderSnapshot.Line line : snapshot.lines()) {
                lines.add(new CustomerOrderMailer.Line(
                        line.description() == null || line.description().isBlank() ? line.sku() : line.description(),
                        line.cartons(), line.piecesPerCarton(), line.quantity(), line.net()));
            }
        }
        boolean pickup = delivery != null ? WebOrderDeliveries.PICKUP.equals(delivery.fulfillment())
                : snapshot != null && WebOrderDeliveries.PICKUP.equals(snapshot.fulfillment());
        /* The page showed a total only for a complete order; otherwise it said "to be confirmed". */
        boolean complete = snapshot != null && snapshot.complete() && totals != null;
        String contact = delivery != null && delivery.contactName() != null && !delivery.contactName().isBlank()
                ? delivery.contactName() : customer.contact();
        return new OrderMail(recipient.to(), language, contact, customer.company(), order.number(), lines,
                totals == null ? null : totals.goods(),
                totals == null ? null : totals.shipping(),
                totals != null && "TO_CONFIRM".equals(totals.shippingStatus()), pickup,
                complete ? totals.totalExclVat() : null,
                complete ? totals.vatAmount() : null,
                complete ? totals.totalInclVat() : null,
                deliveryLine(delivery, snapshot == null ? null : snapshot.countryCode(), language));
    }

    /** "street, postal code city, country", or the pickup point and its address. */
    private static String deliveryLine(WebOrderDeliveries.Delivery delivery, String countryCode, Language language) {
        if (delivery == null) return null;
        String line = WebOrderDeliveries.PICKUP.equals(delivery.fulfillment())
                ? join(", ", delivery.pickupLabel(), delivery.pickupAddress())
                : join(", ", delivery.address(), join(" ", delivery.postalCode(), delivery.city()),
                        countryName(countryCode, language));
        return line.isEmpty() ? null : line;
    }

    private static String countryName(String code, Language language) {
        if (code == null || code.isBlank()) return null;
        String country = code.strip().toUpperCase(Locale.ROOT);
        String name = Locale.of("", country).getDisplayCountry(language.locale());
        return name == null || name.isBlank() ? country : name;
    }

    private static String join(String separator, String... parts) {
        List<String> filled = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) filled.add(part.strip());
        }
        return String.join(separator, filled);
    }

    /** Tests run the deliveries on the test thread or capture them. */
    void useExecutor(Executor executor) {
        this.executor = executor;
    }
}
