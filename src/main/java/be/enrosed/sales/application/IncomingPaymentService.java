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
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public record Request(BigDecimal amountEur, Instant receivedAt, String timeZone, String reference) {}
    public record IncomingPayment(Long id, long salesOrderId, BigDecimal amountEur, Instant receivedAt,
                                  String timeZone, String reference, Instant recordedAt, String actor, boolean legacy,
                                  String orderNumber, Long customerId, Long purchaseOrderId, SalesPurpose purpose) {}

    public List<SalesPayment> forOrder(long id) { sales.get(id); return payments.forOrder(id); }

    /** Voided receipts still belong to the document's financial audit history. */
    public boolean hasHistory(long id) { return payments.everRecorded(id); }

    public List<IncomingPayment> list(LocalDate from) {
        Map<Long, SalesOrder> byId = orders.findAll().stream().collect(Collectors.toMap(SalesOrder::id, Function.identity()));
        return payments.all().stream().filter(p -> from == null
                        || !p.receivedAt().atZone(ZoneId.of(p.timeZone())).toLocalDate().isBefore(from))
                .map(p -> enrich(p, byId.get(p.salesOrderId()))).toList();
    }

    public IncomingPayment enrich(SalesPayment p, SalesOrder order) {
        return new IncomingPayment(p.id(), p.salesOrderId(), p.amountEur(), p.receivedAt(), p.timeZone(), p.reference(),
                p.recordedAt(), p.actor(), p.legacy(), order == null ? null : order.number(),
                order == null ? null : order.customerId(), order == null ? null : order.linkedPurchaseOrderId(),
                order == null ? SalesPurpose.STANDARD : order.purpose());
    }

    public SalesPaymentSummary summary(SalesOrder order, PricedOrder priced) {
        List<SalesPayment> rows = order.id() == null ? List.of() : payments.forOrder(order.id());
        BigDecimal total = Money.money(priced.totals().totalInclVat());
        BigDecimal received = Money.money(rows.stream().map(SalesPayment::amountEur).reduce(ZERO, BigDecimal::add));
        BigDecimal remaining = total.subtract(received).max(ZERO);
        BigDecimal credit = total.signum() < 0 ? total.negate().add(received) : ZERO;
        BigDecimal excess = total.signum() >= 0 ? received.subtract(total).max(ZERO) : ZERO;
        SalesPaymentSummary.Status status = total.signum() < 0 ? SalesPaymentSummary.Status.CREDIT
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
                List.copyOf(instalments), rows.stream().anyMatch(SalesPayment::legacy));
    }

    private void addInstalment(List<SalesPaymentSummary.Instalment> rows, String key, String label,
                              BigDecimal expected, BigDecimal received) {
        BigDecimal paid = received.min(expected).max(ZERO);
        rows.add(new SalesPaymentSummary.Instalment(key, label, expected, paid, expected.subtract(paid)));
    }

    @Transactional
    public SalesOrder add(long id, Request request) {
        orders.lockById(id);
        SalesOrder order = requireReceivable(sales.get(id));
        backfill(order);
        Request clean = validate(request);
        SalesPayment saved = payments.save(new SalesPayment(null, id, clean.amountEur(), clean.receivedAt(),
                clean.timeZone(), clean.reference(), Instant.now(), actorName(), false));
        audit(order, "Inkomende betaling geregistreerd", null, saved);
        return reconcile(order);
    }

    @Transactional
    public SalesOrder update(long id, long paymentId, Request request) {
        orders.lockById(id);
        SalesOrder order = requireReceivable(sales.get(id));
        SalesPayment before = find(id, paymentId);
        Request clean = validate(request);
        SalesPayment saved = payments.save(new SalesPayment(before.id(), id, clean.amountEur(), clean.receivedAt(),
                clean.timeZone(), clean.reference(), before.recordedAt(), before.actor(), before.legacy()));
        audit(order, "Inkomende betaling gecorrigeerd", before, saved);
        return reconcile(order);
    }

    @Transactional
    public void delete(long id, long paymentId) {
        orders.lockById(id);
        SalesOrder order = sales.get(id);
        SalesPayment before = find(id, paymentId);
        payments.voidPayment(paymentId);
        audit(order, "Inkomende betaling ingetrokken", before, null);
        reconcile(order);
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
        if (!order.isInvoice() || order.status() == QuoteStatus.CONCEPT)
            throw new BusinessRuleException("Reik eerst de factuur uit voordat je een ontvangst registreert; verzending is niet verplicht");
        if (order.status() == QuoteStatus.GEANNULEERD || order.status() == QuoteStatus.AFGEWEZEN || order.status() == QuoteStatus.VERLOPEN)
            throw new BusinessRuleException("Deze factuur is niet actief; heropen eerst het document voor een nieuwe ontvangst");
        if (sales.price(order).totals().totalInclVat().signum() <= 0)
            throw new BusinessRuleException("Deze afrekening is een tegoed of nulbedrag; registreer hiervoor geen inkomende betaling");
        return order;
    }

    private Request validate(Request request) {
        if (request == null || request.amountEur() == null || Money.money(request.amountEur()).signum() <= 0)
            throw new BusinessRuleException("Geef een ontvangen bedrag groter dan nul op");
        if (request.receivedAt() == null) throw new BusinessRuleException("Vul de ontvangstdatum en het tijdstip in");
        if (request.receivedAt().isAfter(Instant.now().plusSeconds(300))) throw new BusinessRuleException("Een ontvangst kan niet in de toekomst liggen");
        String zone = request.timeZone() == null || request.timeZone().isBlank() ? "Europe/Brussels" : request.timeZone().strip();
        if (zone.length() > 64) throw new BusinessRuleException("De tijdzone is maximaal 64 tekens");
        try { ZoneId.of(zone); } catch (Exception invalid) { throw new BusinessRuleException("Kies een geldige tijdzone"); }
        String reference = request.reference() == null || request.reference().isBlank() ? null : request.reference().strip();
        if (reference != null && reference.length() > 500) throw new BusinessRuleException("De referentie is maximaal 500 tekens");
        return new Request(Money.money(request.amountEur()), request.receivedAt(), zone, reference);
    }

    private SalesOrder reconcile(SalesOrder order) {
        SalesPaymentSummary summary = summary(order, sales.price(order));
        boolean paid = summary.invoiceTotalEur().signum() > 0 && summary.remainingEur().signum() == 0;
        Instant paidAt = paid ? fullyPaidAt(summary) : null;
        return orders.save(order.withPaymentState(paid ? QuoteStatus.BETAALD
                : order.status() == QuoteStatus.BETAALD ? (order.sentAt() == null ? QuoteStatus.UITGEREIKT : QuoteStatus.VERZONDEN) : order.status(), paidAt));
    }

    /** Later overpayments must not move the moment the invoice first became fully paid. */
    private Instant fullyPaidAt(SalesPaymentSummary summary) {
        BigDecimal cumulative = ZERO;
        for (SalesPayment payment : summary.payments().stream()
                .sorted(Comparator.comparing(SalesPayment::receivedAt).thenComparing(SalesPayment::id)).toList()) {
            cumulative = cumulative.add(payment.amountEur());
            if (cumulative.compareTo(summary.invoiceTotalEur()) >= 0) return payment.receivedAt();
        }
        return null;
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
