package be.enrosed.sales.application;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.adapter.out.persistence.SalesWebOrderEntity;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.QuoteEvent;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.sales.domain.SalesPurpose;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The website order of a logged-in customer: every read and write of its
 * side row, the predicates that say whose it is and whether the customer may
 * still change it, the gate a staff mutation passes, and the rule that
 * nothing is invoiced or approved unless price, quantity and freight are
 * what the customer ordered or approved.
 *
 * A row exists, or the document is not a website order; nothing else decides
 * that. The row lock is taken only by code that already holds the lock of
 * the sales document with the same id.
 */
@ApplicationScoped
public class WebOrders {
    public static final String TRIGGER_BUTTON = "KNOP";
    public static final String TRIGGER_AUTOMATIC = "AUTOMATISCH";

    public record Row(long salesOrderId, Long customerId, Long accountId, String accountEmail, String language,
                      int revision, Instant placedAt, Instant customerChangedAt, String customerChangeSummary,
                      Instant customerCancelledAt, Instant processingStartedAt, String processingStartedBy,
                      String processingTrigger, String orderedTerms, String sentTerms, String acceptedTerms,
                      String orderSnapshot,
                      Instant receivedMailSentAt, Instant processingMailSentAt, String mailError) {}

    /** Where the figures of a website order stand against what the customer ordered, was sent or approved. */
    public enum TermsState { ORDER_EQUAL, ORDER_DIFFERENT, ORDER_UNKNOWN, AWAITING_APPROVAL, RESEND_REQUIRED, APPROVED }

    /** Every column except the snapshot, in the order {@link #stateRow} reads them. */
    private static final String STATE_COLUMNS = "w.salesOrderId, w.customerId, w.accountId, w.accountEmail, w.language, "
            + "w.revision, w.placedAt, w.customerChangedAt, w.customerChangeSummary, w.customerCancelledAt, "
            + "w.processingStartedAt, w.processingStartedBy, w.processingTrigger, w.orderedTerms, w.sentTerms, "
            + "w.acceptedTerms, w.receivedMailSentAt, w.processingMailSentAt, w.mailError";

    @Inject EntityManager entities;
    @Inject CurrentActor actor;
    @Inject SalesRepositories.Events events;
    @Inject Instance<StaffWebOrderRevision> staffRevision;
    @Inject Event<WebOrderEvents.Taken> taken;

    public Optional<Row> find(long orderId) {
        SalesWebOrderEntity entity = entities.find(SalesWebOrderEntity.class, orderId);
        return entity == null ? Optional.empty() : Optional.of(row(entity));
    }

    public Map<Long, Row> index(Collection<Long> orderIds) {
        Map<Long, Row> rows = new LinkedHashMap<>();
        if (orderIds == null || orderIds.isEmpty()) return rows;
        for (SalesWebOrderEntity entity : entities.createQuery(
                        "select w from SalesWebOrderEntity w where w.salesOrderId in :ids", SalesWebOrderEntity.class)
                .setParameter("ids", orderIds).getResultList())
            rows.put(entity.salesOrderId, row(entity));
        return rows;
    }

    /** The staff list and the notification feed: every row in one query, without the snapshot. */
    public Map<Long, Row> indexStates() {
        Map<Long, Row> rows = new LinkedHashMap<>();
        for (Object[] columns : entities.createQuery(
                "select " + STATE_COLUMNS + " from SalesWebOrderEntity w", Object[].class).getResultList()) {
            Row row = stateRow(columns);
            rows.put(row.salesOrderId(), row);
        }
        return rows;
    }

    /** The row lock; the caller holds the lock of the sales document. */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<Row> lock(long orderId) {
        SalesWebOrderEntity entity = locked(orderId);
        return entity == null ? Optional.empty() : Optional.of(row(entity));
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Row create(long orderId, long customerId, long accountId, String accountEmail, String language,
                      String snapshotJson, String orderedTerms) {
        SalesWebOrderEntity entity = new SalesWebOrderEntity();
        entity.salesOrderId = orderId;
        entity.customerId = customerId;
        entity.accountId = accountId;
        entity.accountEmail = cut(accountEmail, 254);
        entity.language = cut(language, 4);
        entity.revision = 1;
        entity.placedAt = Instant.now();
        entity.orderSnapshot = snapshotJson;
        entity.orderedTerms = orderedTerms;
        entities.persist(entity);
        entities.flush();
        return row(entity);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Row recordChange(long orderId, long accountId, String accountEmail, String language, String snapshotJson,
                            String orderedTerms, String summary) {
        SalesWebOrderEntity entity = required(orderId);
        entity.accountId = accountId;
        entity.accountEmail = cut(accountEmail, 254);
        entity.language = cut(language, 4);
        entity.orderSnapshot = snapshotJson;
        entity.orderedTerms = orderedTerms;
        entity.customerChangedAt = Instant.now();
        entity.customerChangeSummary = cut(summary, 1000);
        entity.revision = revision(entity) + 1;
        entities.flush();
        return row(entity);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Row recordCustomerCancel(long orderId) {
        SalesWebOrderEntity entity = required(orderId);
        entity.customerCancelledAt = Instant.now();
        entity.revision = revision(entity) + 1;
        entities.flush();
        return row(entity);
    }

    /** The figures of the version that was mailed; a document without a row has none. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void recordSent(long orderId, String terms) {
        SalesWebOrderEntity entity = locked(orderId);
        if (entity == null) return;
        entity.sentTerms = terms;
        entities.flush();
    }

    /** The figures the customer approved; a document without a row has none. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void recordAccepted(long orderId, String terms) {
        SalesWebOrderEntity entity = locked(orderId);
        if (entity == null) return;
        entity.acceptedTerms = terms;
        entities.flush();
    }

    /**
     * The staff gate, run after the lock of the sales document inside the
     * caller's transaction. A staff action that presents an old revision, or
     * none for an order the customer ever changed, is refused, also after a
     * colleague took the order: its whole-document save would otherwise
     * replace the customer's lines. The first action that passes takes the
     * order into processing; a refused or failing action rolls that back.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void afterStaffLock(long orderId) {
        SalesWebOrderEntity row = locked(orderId);
        if (row == null) return;
        ActorRef who = actor.current();
        if (ActorRef.SYSTEM.equals(who)) return;

        Integer presented = presentedRevision();
        int revision = revision(row);
        if (presented != null && presented != revision) throw new WebOrderChangedException(revision, false);
        if (presented == null && revision > 1) throw new WebOrderChangedException(revision, true);

        if (row.processingStartedAt != null || row.customerCancelledAt != null) return;
        boolean explicit = explicitTake();
        String name = who.displayName() == null || who.displayName().isBlank() ? who.username() : who.displayName();
        row.processingStartedAt = Instant.now();
        row.processingStartedBy = cut(name, 120);
        row.processingTrigger = explicit ? TRIGGER_BUTTON : TRIGGER_AUTOMATIC;
        entities.flush();
        events.add(new QuoteEvent(null, orderId, QuoteEvent.Type.IN_VERWERKING, row.processingStartedAt, name, false,
                explicit ? "In verwerking genomen"
                        : "In verwerking genomen (automatisch bij een wijziging door " + name + ")", null));
        SalesOrderEntity document = entities.find(SalesOrderEntity.class, orderId);
        taken.fire(new WebOrderEvents.Taken(orderId, document == null ? null : document.number));
    }

    /** Whose order: the session's customer placed it and the document is still that customer's. */
    public static boolean owns(SalesOrder order, Row row, long sessionCustomerId) {
        return row != null && order.customerId() != null && order.customerId() == sessionCustomerId
                && Objects.equals(row.customerId(), order.customerId());
    }

    /**
     * Whether the customer may still change or cancel: nobody at Enrosed has
     * touched the order and it is still the plain website concept it was
     * placed as. Ownership is always tested separately and first. The one
     * predicate serves the read side and, after the locks, the write side.
     */
    public static boolean customerMayChange(SalesOrder order, Row row, boolean hasDerivedInvoice) {
        return row != null && row.processingStartedAt() == null && row.customerCancelledAt() == null
                && !order.isClaimDocument() && order.status() == QuoteStatus.CONCEPT
                && "WEBSITE".equalsIgnoreCase(order.rawSalesChannel())
                && order.sentAt() == null && order.portalToken() == null && !order.isArchived()
                && order.purpose() == SalesPurpose.STANDARD && order.partnerPurchaseOrderId() == null
                && !hasDerivedInvoice;
    }

    /** Null when the document is no website order or its status has no state (cancelled, declined, expired, change requested). */
    public static TermsState termsState(SalesOrder order, Row row, String currentTerms) {
        if (row == null) return null;
        QuoteStatus status = order.status();
        if (status == QuoteStatus.GEACCEPTEERD)
            return row.acceptedTerms() == null || row.acceptedTerms().equals(currentTerms)
                    ? TermsState.APPROVED : TermsState.RESEND_REQUIRED;
        if (status == QuoteStatus.VERZONDEN || status == QuoteStatus.BEKEKEN)
            return row.sentTerms() == null || row.sentTerms().equals(currentTerms)
                    ? TermsState.AWAITING_APPROVAL : TermsState.RESEND_REQUIRED;
        if (status != QuoteStatus.CONCEPT) return null;
        if (order.sentAt() != null) return TermsState.RESEND_REQUIRED;
        if (row.orderedTerms() == null) return TermsState.ORDER_UNKNOWN;
        return row.orderedTerms().equals(currentTerms) ? TermsState.ORDER_EQUAL : TermsState.ORDER_DIFFERENT;
    }

    /** An invoice is made only from an order that is unchanged, or from the version the customer approved. */
    public void requireInvoiceable(SalesOrder quote, PricedOrder priced) {
        Row row = quote.id() == null ? null : find(quote.id()).orElse(null);
        if (row == null) return;
        TermsState state = termsState(quote, row, WebOrderTerms.of(priced));
        if (state == TermsState.ORDER_EQUAL || state == TermsState.APPROVED) return;
        if (state == null) throw new BusinessRuleException("Deze websitebestelling kan in deze status niet gefactureerd worden.");
        throw new BusinessRuleException(switch (state) {
            case ORDER_DIFFERENT -> {
                WebOrderSnapshot snapshot = WebOrderSnapshot.fromJson(row.orderSnapshot());
                BigDecimal ordered = snapshot == null || snapshot.totals() == null ? null : snapshot.totals().totalExclVat();
                yield "Deze websitebestelling wijkt af van wat de klant bestelde (besteld "
                        + (ordered == null ? "nog te bevestigen" : WebOrderTerms.euro(ordered)) + " excl. btw, nu "
                        + WebOrderTerms.euro(priced.totals().total()) + "): "
                        + String.join("; ", WebOrderTerms.differences(snapshot, priced))
                        + ". Verstuur ze ter goedkeuring; factureren kan zodra de klant akkoord gaat.";
            }
            case ORDER_UNKNOWN -> "Bij deze websitebestelling stond de vracht of een prijs nog open toen de klant bestelde. "
                    + "Verstuur ze ter goedkeuring; factureren kan zodra de klant akkoord gaat.";
            case AWAITING_APPROVAL -> "Deze websitebestelling wacht nog op het akkoord van de klant op de verstuurde versie. "
                    + "Factureren kan zodra de klant akkoord gaat.";
            default -> quote.status() == QuoteStatus.GEACCEPTEERD
                    ? "De cijfers van deze websitebestelling wijken af van de versie waarmee de klant akkoord ging "
                            + "(een prijslijst, staffel of vrachttabel is sindsdien gewijzigd). "
                            + "Maak een nieuwe kopie met de huidige cijfers en verstuur die ter goedkeuring."
                    : quote.status() == QuoteStatus.CONCEPT
                    ? "Deze websitebestelling is al ter goedkeuring verstuurd en daarna heropend. "
                            + "Verstuur de nieuwe versie; factureren kan zodra de klant akkoord gaat."
                    : "De cijfers van deze websitebestelling zijn gewijzigd sinds de versie die de klant kreeg. "
                            + "Verstuur ze opnieuw; factureren kan zodra de klant akkoord gaat.";
        });
    }

    /**
     * A draft invoice is an ordinary editable concept, so the comparison is
     * repeated when it stops being a draft: what is issued equals what the
     * customer ordered, or the version the customer approved.
     */
    public void requireIssuable(SalesOrder invoice, PricedOrder pricedInvoice, SalesOrder sourceQuote,
                                BigDecimal deductedAdvancesExcl) {
        if (invoice.sourceQuoteId() == null) return;
        Row row = find(invoice.sourceQuoteId()).orElse(null);
        if (row == null) return;
        String actual = WebOrderTerms.of(pricedInvoice, deductedAdvancesExcl);
        if (sourceQuote != null && sourceQuote.status() == QuoteStatus.GEACCEPTEERD) {
            /* Of the approved version only the fingerprint is kept, so there is no list of differences here. */
            if (row.acceptedTerms() != null && !row.acceptedTerms().equals(actual))
                throw new BusinessRuleException("Deze factuur wijkt af van de versie waarmee de klant akkoord ging. "
                        + "Zet de factuur terug gelijk aan die versie, of verwijder het concept, "
                        + "maak een nieuwe kopie van de offerte en verstuur die ter goedkeuring.");
            return;
        }
        if (row.orderedTerms() != null && row.orderedTerms().equals(actual)) return;
        List<String> differences = WebOrderTerms.differences(WebOrderSnapshot.fromJson(row.orderSnapshot()),
                pricedInvoice, deductedAdvancesExcl);
        throw new BusinessRuleException("Deze factuur wijkt af van wat de klant bestelde"
                + (differences.isEmpty() ? "" : ": " + String.join("; ", differences))
                + ". Zet de factuur terug gelijk aan de bestelling, of verwijder het concept en verstuur de bestelling ter goedkeuring.");
    }

    /** The customer approves only the version that was mailed; the portal shows its own "being updated" message. */
    public void requireAcceptable(SalesOrder quote, PricedOrder priced) {
        Row row = quote.id() == null ? null : find(quote.id()).orElse(null);
        if (row == null || row.sentTerms() == null || row.sentTerms().equals(WebOrderTerms.of(priced))) return;
        throw new BusinessRuleException("Deze offerte wordt momenteel bijgewerkt. "
                + "De nieuwe versie is pas zichtbaar nadat Enrosed ze opnieuw heeft verstuurd.");
    }

    /** Two deliveries with freight per part are a change the customer must approve first. */
    public void requireSplittable(SalesOrder source, SalesOrder sourceQuoteOrNull) {
        boolean order = source.id() != null && source.status() != QuoteStatus.GEACCEPTEERD && find(source.id()).isPresent();
        boolean invoiceOfOrder = source.isInvoice() && sourceQuoteOrNull != null && sourceQuoteOrNull.id() != null
                && sourceQuoteOrNull.status() != QuoteStatus.GEACCEPTEERD && find(sourceQuoteOrNull.id()).isPresent();
        if (order || invoiceOfOrder)
            throw new BusinessRuleException("Een websitebestelling splits je pas nadat de klant akkoord ging. "
                    + "Verstuur ze ter goedkeuring; maak na het akkoord de conceptfactuur en splits die.");
    }

    public void requireNoPartnerDeal(SalesOrder order) {
        if (order.id() != null && find(order.id()).isPresent())
            throw new BusinessRuleException("Een websitebestelling koppel je niet aan een partnerdeal: "
                    + "de klant zou ze niet meer zien onder Mijn bestellingen. Maak een nieuwe kopie en koppel die.");
    }

    public void requireDeletable(SalesOrder order) {
        if (order.id() != null && order.status() != QuoteStatus.GEANNULEERD && order.status() != QuoteStatus.AFGEWEZEN
                && find(order.id()).isPresent())
            throw new BusinessRuleException("Een websitebestelling verwijder je niet zolang ze niet geannuleerd is. "
                    + "Annuleer ze: dan ziet de klant dat onder Mijn bestellingen.");
    }

    public void requireReopenable(SalesOrder order) {
        if (order.id() != null && find(order.id()).filter(row -> row.customerCancelledAt() != null).isPresent())
            throw new BusinessRuleException("Deze bestelling is door de klant geannuleerd. "
                    + "Maak een nieuwe offerte als de klant toch wil bestellen.");
    }

    /** find with the row lock does not refresh an instance this transaction read earlier, hence the refresh. */
    private SalesWebOrderEntity locked(long orderId) {
        SalesWebOrderEntity entity = entities.find(SalesWebOrderEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (entity != null) entities.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }

    private SalesWebOrderEntity required(long orderId) {
        SalesWebOrderEntity entity = locked(orderId);
        if (entity == null) throw new IllegalStateException("Geen websitebestelling: " + orderId);
        return entity;
    }

    /** The revision the staff screen worked from; null without a request or without the parameter. */
    private Integer presentedRevision() {
        try {
            return staffRevision == null || !staffRevision.isResolvable() ? null : staffRevision.get().value();
        } catch (RuntimeException outsideRequest) {
            return null;
        }
    }

    private boolean explicitTake() {
        try {
            return staffRevision != null && staffRevision.isResolvable() && staffRevision.get().explicitTake();
        } catch (RuntimeException outsideRequest) {
            return false;
        }
    }

    private static int revision(SalesWebOrderEntity entity) {
        return entity.revision == null ? 1 : entity.revision;
    }

    private static Row row(SalesWebOrderEntity entity) {
        return new Row(entity.salesOrderId, entity.customerId, entity.accountId, entity.accountEmail, entity.language,
                revision(entity), entity.placedAt, entity.customerChangedAt, entity.customerChangeSummary,
                entity.customerCancelledAt, entity.processingStartedAt, entity.processingStartedBy,
                entity.processingTrigger, entity.orderedTerms, entity.sentTerms, entity.acceptedTerms,
                entity.orderSnapshot, entity.receivedMailSentAt, entity.processingMailSentAt, entity.mailError);
    }

    private static Row stateRow(Object[] c) {
        return new Row((Long) c[0], (Long) c[1], (Long) c[2], (String) c[3], (String) c[4],
                c[5] == null ? 1 : (Integer) c[5], (Instant) c[6], (Instant) c[7], (String) c[8], (Instant) c[9],
                (Instant) c[10], (String) c[11], (String) c[12], (String) c[13], (String) c[14], (String) c[15],
                null, (Instant) c[16], (Instant) c[17], (String) c[18]);
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
