package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Money;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class IncomingPaymentService {
    @Inject SalesOrderService sales;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject IncomingPayments payments;
    @Inject Instance<CurrentActor> actor;
    @Inject Instance<ActivityLogService> activity;
    @Inject Instance<be.enrosed.finance.banking.BankStatementService> banking;
    @Inject Instance<be.enrosed.sourcing.application.PurchaseOrderService> purchases;
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final String BRUSSELS = "Europe/Brussels";

    public enum Direction { RECEIPT, REFUND }
    public record Request(BigDecimal amountEur, Instant receivedAt, String timeZone, String reference, Direction direction, String bankAccount) {
        public Request(BigDecimal amountEur, Instant receivedAt, String timeZone, String reference) {
            this(amountEur, receivedAt, timeZone, reference, null, null);
        }
    }
    public record IncomingPayment(Long id, long salesOrderId, BigDecimal amountEur, Instant receivedAt,
                                  String timeZone, String reference, Instant recordedAt, String actor, boolean legacy,
                                  String orderNumber, Long customerId, Long purchaseOrderId, SalesPurpose purpose, String bankAccount,
                                  /** Offset rows: the other document of the pair and its row; null on bank movements. */
                                  Long offsetOrderId, String offsetOrderNumber, Long offsetPaymentId, DocumentType docType) {
        public IncomingPayment(Long id, long salesOrderId, BigDecimal amountEur, Instant receivedAt, String timeZone,
                               String reference, Instant recordedAt, String actor, boolean legacy, String orderNumber,
                               Long customerId, Long purchaseOrderId, SalesPurpose purpose, String bankAccount) {
            this(id, salesOrderId, amountEur, receivedAt, timeZone, reference, recordedAt, actor, legacy, orderNumber,
                    customerId, purchaseOrderId, purpose, bankAccount, null, null, null, null);
        }
    }

    public List<SalesPayment> forOrder(long id) { sales.get(id); return payments.forOrder(id); }

    /** Voided receipts still belong to the document's financial audit history. */
    public boolean hasHistory(long id) { return payments.everRecorded(id); }

    public List<IncomingPayment> list(LocalDate from) {
        Map<Long, SalesOrder> byId = orders.findAll().stream().collect(Collectors.toMap(SalesOrder::id, Function.identity()));
        return payments.all().stream().filter(p -> from == null
                        || !p.receivedAt().atZone(ZoneId.of(p.timeZone())).toLocalDate().isBefore(from))
                .map(p -> enrich(p, byId.get(p.salesOrderId()), p.offsetOrderId() == null ? null : byId.get(p.offsetOrderId()))).toList();
    }

    public IncomingPayment enrich(SalesPayment p, SalesOrder order) {
        SalesOrder other = p.offsetOrderId() == null ? null : orders.findById(p.offsetOrderId()).orElse(null);
        return enrich(p, order, other);
    }

    private static IncomingPayment enrich(SalesPayment p, SalesOrder order, SalesOrder offsetOrder) {
        return new IncomingPayment(p.id(), p.salesOrderId(), p.amountEur(), p.receivedAt(), p.timeZone(), p.reference(),
                p.recordedAt(), p.actor(), p.legacy(), order == null ? null : order.number(),
                order == null ? null : order.customerId(), order == null ? null : order.linkedPurchaseOrderId(),
                order == null ? SalesPurpose.STANDARD : order.purpose(), p.bankAccount(),
                p.offsetOrderId(), offsetOrder == null ? null : offsetOrder.number(), p.offsetPaymentId(),
                order == null ? null : order.docType());
    }

    /** A credit note reads as a negative claim: the existing credit, refund and settled logic then applies unchanged. */
    public SalesPaymentSummary summary(SalesOrder order, PricedOrder priced) {
        List<SalesPayment> rows = order.id() == null ? List.of() : payments.forOrder(order.id());
        BigDecimal total = DocumentSign.claim(order, priced);
        BigDecimal received = Money.money(rows.stream().map(SalesPayment::amountEur).reduce(ZERO, BigDecimal::add));
        BigDecimal remaining = total.signum() > 0 ? total.subtract(received).max(ZERO) : ZERO;
        BigDecimal gross = rows.stream().map(SalesPayment::amountEur).filter(a -> a.signum() > 0).reduce(ZERO, BigDecimal::add);
        BigDecimal refunded = gross.subtract(received);
        BigDecimal credit = total.signum() < 0 ? total.negate().add(received).max(ZERO) : ZERO;
        BigDecimal excess = total.signum() >= 0 ? received.subtract(total).max(ZERO) : ZERO;
        SalesPaymentSummary.Status status = total.signum() < 0 ? (credit.signum() > 0 ? SalesPaymentSummary.Status.CREDIT : SalesPaymentSummary.Status.PAID)
                : excess.signum() > 0 ? SalesPaymentSummary.Status.OVERPAID
                : received.compareTo(total) >= 0 ? SalesPaymentSummary.Status.PAID
                : received.signum() > 0 ? SalesPaymentSummary.Status.PARTIAL : SalesPaymentSummary.Status.UNPAID;
        List<SalesPaymentSummary.Instalment> instalments = new ArrayList<>();
        if (total.signum() > 0) {
            BigDecimal first = order.paymentPlan() == SalesPaymentPlan.THIRD_TWO_THIRDS_PRODUCTION
                    ? total.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP) : total;
            addInstalment(instalments, order.paymentPlan() == SalesPaymentPlan.FULL ? "FULL" : "PRODUCTION_START",
                    order.paymentPlan() == SalesPaymentPlan.FULL ? "Volledig factuurbedrag" : "1/3 bij start productie", first, received);
            if (order.paymentPlan() == SalesPaymentPlan.THIRD_TWO_THIRDS_PRODUCTION) {
                addInstalment(instalments, "PRODUCTION_COMPLETE", "2/3 na productie", total.subtract(first), received.subtract(first).max(ZERO));
            }
        }
        return new SalesPaymentSummary(total, received, remaining, excess, credit, status, rows,
                List.copyOf(instalments), rows.stream().anyMatch(SalesPayment::legacy), gross, refunded, excess.add(credit));
    }

    private void addInstalment(List<SalesPaymentSummary.Instalment> rows, String key, String label,
                              BigDecimal expected, BigDecimal received) {
        BigDecimal paid = received.min(expected).max(ZERO);
        rows.add(new SalesPaymentSummary.Instalment(key, label, expected, paid, expected.subtract(paid)));
    }

    @Transactional
    public SalesOrder add(long id, Request request) {
        orders.lockById(id);
        SalesOrder order = requireActiveInvoice(sales.get(id));
        backfill(order);
        Request clean = validate(request);
        BigDecimal signed = clean.direction() == Direction.REFUND ? clean.amountEur().negate() : clean.amountEur();
        if (signed.signum() > 0) requireReceivable(order);
        validateNet(order, null, signed);
        SalesPayment saved = payments.save(new SalesPayment(null, id, signed, clean.receivedAt(),
                clean.timeZone(), clean.reference(), Instant.now(), actorName(), false, clean.bankAccount()));
        audit(order, signed.signum() < 0 ? "Terugbetaling geregistreerd" : "Inkomende betaling geregistreerd", null, saved);
        return reconcile(order);
    }

    @Transactional
    public SalesOrder update(long id, long paymentId, Request request) {
        orders.lockById(id);
        SalesOrder order = requireActiveInvoice(sales.get(id));
        SalesPayment before = find(id, paymentId);
        if (before.isOffset())
            throw new BusinessRuleException("Een verrekening corrigeer je door ze in te trekken en opnieuw te maken");
        requireBankUnlinked(paymentId);
        Request clean = validate(request);
        boolean refund = before.amountEur().signum() < 0;
        if (request.direction() != null && (request.direction() == Direction.REFUND) != refund)
            throw new BusinessRuleException("Een correctie mag de richting niet veranderen; trek de beweging in en registreer de juiste betaling");
        BigDecimal signed = refund ? clean.amountEur().negate() : clean.amountEur();
        validateNet(order, paymentId, signed);
        SalesPayment saved = payments.save(new SalesPayment(before.id(), id, signed, clean.receivedAt(),
                clean.timeZone(), clean.reference(), before.recordedAt(), before.actor(), before.legacy(),
                clean.bankAccount() == null ? before.bankAccount() : clean.bankAccount()));
        audit(order, refund ? "Terugbetaling gecorrigeerd" : "Inkomende betaling gecorrigeerd", before, saved);
        return reconcile(order);
    }

    @Transactional
    public void delete(long id, long paymentId) {
        SalesPayment peek = find(id, paymentId);
        if (peek.isOffset()) { retractOffset(id, peek); return; }
        orders.lockById(id);
        SalesOrder order = sales.get(id);
        SalesPayment before = find(id, paymentId);
        requireBankUnlinked(paymentId);
        validateNet(order, paymentId, ZERO);
        payments.voidPayment(paymentId);
        audit(order, before.amountEur().signum() < 0 ? "Terugbetaling ingetrokken" : "Inkomende betaling ingetrokken", before, null);
        reconcile(order);
    }

    /**
     * Offsets a credit note's open balance against an open invoice of the
     * same customer: a negative row on the credit note, a positive row on the
     * invoice, cross-linked, no bank money. Both documents are locked in
     * ascending id order, their container first for partner documents.
     */
    @Transactional
    public SalesOrder applyCredit(long creditId, long invoiceId, BigDecimal amountEur) {
        if (creditId == invoiceId) throw new BusinessRuleException("Verrekenen kan alleen met een andere factuur");
        SalesOrder creditPeek = sales.get(creditId);
        SalesOrder invoicePeek = sales.get(invoiceId);
        requireSameDeal(creditPeek, invoicePeek);
        lockPair(creditPeek, invoicePeek);
        SalesOrder credit = sales.get(creditId);
        SalesOrder invoice = sales.get(invoiceId);
        requireSameDeal(credit, invoice);
        if (!credit.isCreditNote() || credit.status() == QuoteStatus.CONCEPT || !PartnerFinancingService.live(credit))
            throw new BusinessRuleException("Reik de creditnota eerst uit");
        if (!invoice.isInvoice() || invoice.status() == QuoteStatus.CONCEPT || !PartnerFinancingService.live(invoice))
            throw new BusinessRuleException("Factuur " + invoice.number() + " is niet actief of nog een concept");
        if (!Objects.equals(credit.customerId(), invoice.customerId()))
            throw new BusinessRuleException("Verrekenen kan alleen met een factuur van dezelfde klant");
        BigDecimal open = summary(credit, sales.price(credit)).creditEur()
                .min(summary(invoice, sales.price(invoice)).remainingEur());
        if (open.signum() <= 0)
            throw new BusinessRuleException("Er valt niets te verrekenen: de creditnota is afgehandeld of de factuur is al betaald");
        BigDecimal amount = amountEur == null ? open : Money.money(amountEur);
        if (amount.signum() <= 0) throw new BusinessRuleException("Geef een te verrekenen bedrag groter dan nul op");
        if (amount.compareTo(open) > 0) throw new BusinessRuleException("Je kunt hoogstens " + eur(open) + " verrekenen");
        validateNet(credit, null, amount.negate());
        validateNet(invoice, null, amount);
        Instant now = Instant.now();
        String actor = actorName();
        String reference = "Verrekening " + credit.number() + " met " + invoice.number();
        var rows = payments.saveOffsetPair(
                new SalesPayment(null, creditId, amount.negate(), now, BRUSSELS, reference, now, actor, false, null),
                new SalesPayment(null, invoiceId, amount, now, BRUSSELS, reference, now, actor, false, null));
        String creditSummary = "Verrekend " + eur(amount) + " met " + invoice.number();
        String invoiceSummary = "Verrekend " + eur(amount) + " met " + credit.number();
        events.add(new QuoteEvent(null, creditId, QuoteEvent.Type.VERREKEND, now, actor, false, creditSummary, rows.get(0).toString()));
        events.add(new QuoteEvent(null, invoiceId, QuoteEvent.Type.VERREKEND, now, actor, false, invoiceSummary, rows.get(1).toString()));
        recordActivity(credit, creditSummary);
        recordActivity(invoice, invoiceSummary);
        SalesOrder settled = reconcile(credit);
        reconcile(invoice);
        return settled;
    }

    /** Voids both halves of an offset under both locks; the history keeps the rows. */
    private void retractOffset(long id, SalesPayment row) {
        SalesOrder here = sales.get(id);
        SalesOrder there = sales.get(row.offsetOrderId());
        lockPair(here, there);
        here = sales.get(id);
        there = sales.get(row.offsetOrderId());
        SalesPayment mine = find(id, row.id());
        SalesPayment counterpart = payments.forOrder(there.id()).stream()
                .filter(p -> Objects.equals(p.id(), row.offsetPaymentId())).findFirst().orElse(null);
        validateNet(here, mine.id(), ZERO);
        if (counterpart != null) validateNet(there, counterpart.id(), ZERO);
        payments.voidPayment(mine.id());
        audit(here, "Verrekening ingetrokken", mine, null);
        if (counterpart != null) {
            payments.voidPayment(counterpart.id());
            audit(there, "Verrekening ingetrokken", counterpart, null);
        }
        reconcile(here);
        reconcile(there);
    }

    /** Partner documents only offset within their own container; ordinary documents only among themselves. */
    private static void requireSameDeal(SalesOrder credit, SalesOrder invoice) {
        if (!credit.isPartnerDeal() && !invoice.isPartnerDeal()) return;
        if (!credit.isPartnerDeal() || !invoice.isPartnerDeal()
                || !Objects.equals(credit.linkedPurchaseOrderId(), invoice.linkedPurchaseOrderId()))
            throw new BusinessRuleException("Verrekenen tussen een partnerdocument en een gewoon document is niet mogelijk");
    }

    /** The container first, then both documents by ascending id, so two offsets can never wait on each other. */
    private void lockPair(SalesOrder left, SalesOrder right) {
        if (left.isPartnerDeal() && left.linkedPurchaseOrderId() != null && purchases != null && purchases.isResolvable())
            purchases.get().lockForPartnerSettlement(left.linkedPurchaseOrderId());
        long first = Math.min(left.id(), right.id());
        long second = Math.max(left.id(), right.id());
        orders.lockById(first);
        if (second != first) orders.lockById(second);
    }

    private void recordActivity(SalesOrder order, String summary) {
        if (activity != null && activity.isResolvable()) activity.get().record(ActivityLogService.ACTION_UPDATED,
                "SALES_ORDER", order.id().toString(), order.number(), summary);
    }

    static String eur(BigDecimal amount) {
        return "€ " + String.format(java.util.Locale.forLanguageTag("nl-BE"), "%,.2f", Money.money(amount));
    }

    @Transactional
    public SalesOrder markPaid(long id) {
        orders.lockById(id);
        SalesOrder order = requireReceivable(sales.get(id));
        backfill(order);
        BigDecimal remaining = summary(order, sales.price(order)).remainingEur();
        if (remaining.signum() <= 0) return reconcile(order);
        return add(id, new Request(remaining, Instant.now(), "Europe/Brussels", "Volledige betaling geregistreerd"));
    }

    /** Runs once per old paid marker; even a later void never recreates that receipt. */
    @Transactional
    public void backfill(SalesOrder order) {
        if (!order.isInvoice() || order.paidAt() == null || payments.everRecorded(order.id())) return;
        orders.lockById(order.id());
        if (payments.everRecorded(order.id())) return;
        BigDecimal total = Money.money(sales.price(order).totals().totalInclVat());
        if (total.signum() <= 0) return;
        payments.save(new SalesPayment(null, order.id(), total, order.paidAt(), "Europe/Brussels",
                "Historische betaalmarkering", order.paidAt(), "Historische registratie", true));
    }

    /** Isolate failures in unusual historical data so healthy receipts still migrate. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void backfillOne(long id) { backfill(sales.get(id)); }

    private SalesPayment find(long id, long paymentId) {
        return payments.forOrder(id).stream().filter(p -> p.id() == paymentId).findFirst()
                .orElseThrow(() -> new NotFoundException("Inkomende betaling", paymentId));
    }

    private SalesOrder requireReceivable(SalesOrder order) {
        requireActiveInvoice(order);
        if (order.isCreditNote())
            throw new BusinessRuleException("Op een creditnota registreer je geen ontvangst; verreken ze met een factuur of noteer een terugbetaling");
        if (sales.price(order).totals().totalInclVat().signum() <= 0)
            throw new BusinessRuleException("Deze afrekening is een tegoed of nulbedrag; registreer hiervoor geen inkomende betaling");
        return order;
    }

    private SalesOrder requireActiveInvoice(SalesOrder order) {
        if (!order.isClaimDocument() || order.status() == QuoteStatus.CONCEPT)
            throw new BusinessRuleException(order.isCreditNote()
                    ? "Reik eerst de creditnota uit voordat je een terugbetaling of verrekening registreert"
                    : "Reik eerst de factuur uit voordat je een ontvangst registreert; verzending is niet verplicht");
        if (order.status() == QuoteStatus.GEANNULEERD || order.status() == QuoteStatus.AFGEWEZEN || order.status() == QuoteStatus.VERLOPEN)
            throw new BusinessRuleException("Deze factuur is niet actief; heropen eerst het document voor een nieuwe ontvangst");
        return order;
    }

    private void validateNet(SalesOrder order, Long replacingId, BigDecimal replacement) {
        BigDecimal total = DocumentSign.claim(order, sales.price(order));
        var retained = payments.forOrder(order.id()).stream().filter(p -> !Objects.equals(p.id(), replacingId)).toList();
        BigDecimal net = retained.stream().map(SalesPayment::amountEur).reduce(replacement, BigDecimal::add);
        boolean hasRefund = replacement.signum() < 0 || retained.stream().anyMatch(p -> p.amountEur().signum() < 0);
        if (net.compareTo(total.min(ZERO)) < 0 || hasRefund && total.signum() >= 0 && net.compareTo(total) < 0)
            throw new BusinessRuleException("De terugbetaling overschrijdt het tegoed. Corrigeer eerst de gekoppelde terugbetaling voordat je de ontvangst vermindert of intrekt");
    }

    private Request validate(Request request) {
        if (request == null || request.amountEur() == null || Money.money(request.amountEur()).signum() <= 0)
            throw new BusinessRuleException("Geef een ontvangen bedrag groter dan nul op");
        if (Money.money(request.amountEur()).precision() > 19) throw new BusinessRuleException("Het bedrag is te groot");
        if (request.receivedAt() == null) throw new BusinessRuleException("Vul de ontvangstdatum en het tijdstip in");
        if (request.receivedAt().isAfter(Instant.now().plusSeconds(300))) throw new BusinessRuleException("Een ontvangst kan niet in de toekomst liggen");
        String zone = request.timeZone() == null || request.timeZone().isBlank() ? "Europe/Brussels" : request.timeZone().strip();
        if (zone.length() > 64) throw new BusinessRuleException("De tijdzone is maximaal 64 tekens");
        try { ZoneId.of(zone); } catch (Exception invalid) { throw new BusinessRuleException("Kies een geldige tijdzone"); }
        String reference = request.reference() == null || request.reference().isBlank() ? null : request.reference().strip();
        if (reference != null && reference.length() > 500) throw new BusinessRuleException("De referentie is maximaal 500 tekens");
        String account = normalizeAccount(request.bankAccount());
        return new Request(Money.money(request.amountEur()), request.receivedAt(), zone, reference, request.direction(), account);
    }

    public static String normalizeAccount(String value) {
        if (value == null || value.isBlank()) return null;
        String account = value.strip().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
        if (account.matches("[A-Z]{2}[0-9]{2}[A-Z0-9 ]{11,34}")) account = account.replace(" ", "");
        if (account.length() > 120) throw new BusinessRuleException("De bankrekening is maximaal 120 tekens");
        return account;
    }

    private void requireBankUnlinked(long id) {
        if (banking != null && banking.isResolvable() && banking.get().paymentLinked(id))
            throw new BusinessRuleException("Deze betaling is gekoppeld aan een bankbeweging; maak eerst de bankkoppeling ongedaan voordat je corrigeert");
    }

    private SalesOrder reconcile(SalesOrder order) {
        SalesPaymentSummary summary = summary(order, sales.price(order));
        boolean paid = summary.invoiceTotalEur().signum() != 0 && summary.remainingEur().signum() == 0 && summary.creditEur().signum() == 0;
        Instant paidAt = paid ? fullyPaidAt(summary) : null;
        boolean inactive = order.status() == QuoteStatus.GEANNULEERD || order.status() == QuoteStatus.AFGEWEZEN || order.status() == QuoteStatus.VERLOPEN;
        return orders.save(order.withPaymentState(inactive ? order.status() : paid ? QuoteStatus.BETAALD
                : order.status() == QuoteStatus.BETAALD ? (order.sentAt() == null ? QuoteStatus.UITGEREIKT : QuoteStatus.VERZONDEN) : order.status(), paidAt));
    }

    /** Later overpayments must not move the moment the invoice first became fully paid. */
    private Instant fullyPaidAt(SalesPaymentSummary summary) {
        BigDecimal cumulative = ZERO;
        Instant completed = null;
        for (SalesPayment payment : summary.payments().stream()
                .sorted(Comparator.comparing(SalesPayment::receivedAt).thenComparing(SalesPayment::id)).toList()) {
            cumulative = cumulative.add(payment.amountEur());
            boolean covered = summary.invoiceTotalEur().signum() < 0
                    ? cumulative.compareTo(summary.invoiceTotalEur()) <= 0 : cumulative.compareTo(summary.invoiceTotalEur()) >= 0;
            if (!covered) completed = null;
            else if (completed == null) completed = payment.receivedAt();
        }
        return completed;
    }

    private String actorName() { return actor != null && actor.isResolvable() ? actor.get().current().displayName() : "Systeem"; }

    private void audit(SalesOrder order, String action, SalesPayment before, SalesPayment after) {
        String detail = "Voor: " + Objects.toString(before, "—") + "; na: " + Objects.toString(after, "—");
        events.add(new QuoteEvent(null, order.id(), QuoteEvent.Type.BETAALD, Instant.now(), actorName(), false, action, detail));
        if (activity != null && activity.isResolvable()) activity.get().record(ActivityLogService.ACTION_UPDATED,
                "SALES_ORDER", order.id().toString(), order.number(), action + " · € "
                        + (after == null ? before.amountEur() : after.amountEur()));
    }
}
