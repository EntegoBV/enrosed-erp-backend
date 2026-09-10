package be.enrosed.sales.application;

import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Money;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.PurchaseOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A financing agreement and its invoices are separate from bank receipts and profit sharing. */
@ApplicationScoped
public class PartnerAdvanceScheduleService {
    @Inject PartnerAdvanceSchedules schedules;
    @Inject PurchaseOrderService purchases;
    @Inject SalesOrderService sales;
    @Inject SalesRepositories.Orders orders;
    @Inject IncomingPaymentService incoming;
    @Inject PartnerAdvanceQuotes advanceQuotes;
    @Inject Instance<ActivityLogService> activity;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public record RowRequest(Long id, String label, BigDecimal percentage, BigDecimal amountEur, LocalDate dueDate) {}
    public record Request(List<RowRequest> rows, boolean recalculateAgreement) {}
    public record Row(Long id, String label, BigDecimal percentage, BigDecimal amountEur, LocalDate dueDate,
                      Long invoiceId, String invoiceNumber, QuoteStatus invoiceStatus, BigDecimal receivedEur,
                      BigDecimal remainingEur) {}
    public record Schedule(long purchaseOrderId, long partnerCustomerId, BigDecimal agreedAmountEur,
                           BigDecimal financingPct, BigDecimal externalCostEur, BigDecimal allocatedEur,
                           BigDecimal unallocatedEur, BigDecimal reservedOutsideScheduleEur, List<Row> rows,
                           boolean invoicingBlocked, String invoicingBlockedReason) {}

    public Schedule get(long purchaseId) {
        PurchaseOrder purchase = purchases.get(purchaseId);
        var agreement = schedules.find(purchaseId);
        if (agreement == null) agreement = calculatedAgreement(purchase);
        var rows = schedules.rows(purchaseId);
        BigDecimal outside = outsideReservations(purchaseId, rows, null);
        BigDecimal allocated = sumRows(rows).add(outside);
        return new Schedule(purchaseId, agreement.partnerCustomerId(), agreement.agreedAmountEur(), agreement.financingPct(),
                agreement.externalCostEur(), allocated, agreement.agreedAmountEur().subtract(allocated).max(ZERO), outside,
                rows.stream().map(row -> {
                    SalesOrder invoice = row.invoiceId() == null ? null : sales.get(row.invoiceId());
                    SalesPaymentSummary paid = invoice == null ? null : incoming.summary(invoice, sales.price(invoice));
                    return new Row(row.id(), row.label(), row.percentage(), row.amountEur(), row.dueDate(), row.invoiceId(),
                            invoice == null ? null : invoice.number(), invoice == null ? null : invoice.status(),
                            paid == null ? ZERO : paid.receivedEur(), paid == null ? ZERO : paid.remainingEur());
                }).toList(), hasSettlement(purchaseId), hasSettlement(purchaseId)
                    ? "Er bestaat al een afrekening; nieuwe voorschotfacturen zijn niet meer mogelijk." : null);
    }

    public boolean hasRows(long purchaseId) { return !schedules.rows(purchaseId).isEmpty(); }

    /** The saved financing plan creates only its own draft invoices, never a second full claim. */
    @Transactional
    public List<SalesOrder> createInvoices(long purchaseId, Request request) {
        PurchaseOrder purchase = purchases.lockForPartnerSettlement(purchaseId);
        Schedule plan = request == null || sameSavedPlan(purchase, request) ? get(purchaseId) : save(purchaseId, request);
        if (plan.agreedAmountEur().signum() <= 0 || plan.rows().isEmpty())
            throw new BusinessRuleException("Bij deze partnerafspraak is geen voorschotfactuur nodig; leg de afspraak vast op de inkooporder");
        BigDecimal planned = plan.rows().stream().map(Row::amountEur).reduce(ZERO, BigDecimal::add);
        if (planned.compareTo(plan.agreedAmountEur()) != 0)
            throw new BusinessRuleException("De voorschottermijnen moeten samen het afgesproken voorschot van € " + plan.agreedAmountEur() + " dekken");
        List<SalesOrder> invoices = new ArrayList<>();
        for (Row row : plan.rows()) invoices.add(createInvoice(purchaseId, row.id()));
        return List.copyOf(invoices);
    }

    /** Browser retries can still contain null row ids from before the first save. */
    private boolean sameSavedPlan(PurchaseOrder purchase, Request request) {
        var agreement = schedules.find(purchase.id());
        var rows = schedules.rows(purchase.id());
        if (agreement == null || request.rows() == null || request.rows().size() != rows.size()) return false;
        if (request.recalculateAgreement()) {
            var current = calculatedAgreement(purchase);
            if (!same(agreement.agreedAmountEur(), current.agreedAmountEur())
                    || !same(agreement.externalCostEur(), current.externalCostEur())
                    || !same(agreement.financingPct(), current.financingPct())) return false;
        }
        for (int index = 0; index < rows.size(); index++) {
            var requested = request.rows().get(index); var saved = rows.get(index);
            if (requested == null || requested.id() != null && !requested.id().equals(saved.id())
                    || !Objects.equals(requested.label() == null ? null : requested.label().strip(), saved.label())
                    || !same(requested.percentage(), saved.percentage())
                    || requested.percentage() == null && !same(requested.amountEur(), saved.amountEur())
                    || requested.percentage() != null && requested.amountEur() != null
                    || !Objects.equals(requested.dueDate(), saved.dueDate())) return false;
        }
        return true;
    }

    /** Saving the plan and freezing the customer quotation share the caller's transaction. */
    @Transactional
    public PartnerAdvanceQuotes.Snapshot quoteArrangements(long purchaseId, Request request, BigDecimal sharePct) {
        Schedule schedule = request == null ? get(purchaseId) : save(purchaseId, request);
        if (schedule.rows().isEmpty() && schedule.agreedAmountEur().signum() != 0)
            throw new BusinessRuleException("Voeg minstens één voorschottermijn toe aan de offerte");
        BigDecimal quoted = schedule.rows().stream().map(Row::amountEur).reduce(ZERO, BigDecimal::add);
        if (quoted.compareTo(schedule.agreedAmountEur()) != 0)
            throw new BusinessRuleException("De offertetermijnen moeten samen het afgesproken voorschot van € " + schedule.agreedAmountEur() + " dekken");
        if (schedule.invoicingBlocked())
            throw new BusinessRuleException("Er bestaat al een afrekening; maak geen nieuwe voorschotofferte voor deze inkooporder");
        return new PartnerAdvanceQuotes.Snapshot(purchaseId, schedule.financingPct(), schedule.agreedAmountEur(), sharePct,
                schedule.rows().stream().map(row -> new PartnerAdvanceQuotes.Row(row.id(), row.label(), row.percentage(),
                        row.amountEur(), row.dueDate())).toList());
    }

    @Transactional
    public Schedule save(long purchaseId, Request request) {
        PurchaseOrder purchase = purchases.lockForPartnerSettlement(purchaseId);
        if (request == null || request.rows() == null || request.rows().size() > 50)
            throw new BusinessRuleException("Geef maximaal 50 voorschottermijnen op");
        var current = schedules.rows(purchaseId);
        var existing = current.stream().collect(Collectors.toMap(PartnerAdvanceSchedules.Row::id, Function.identity()));
        var agreement = schedules.find(purchaseId);
        if (request.recalculateAgreement() && (hasSettlement(purchaseId) || !advanceInvoices(purchaseId).isEmpty() || current.stream().anyMatch(row -> row.invoiceId() != null)))
            throw new BusinessRuleException("De financieringsbasis kan niet meer worden herberekend zodra een voorschotfactuur bestaat");
        if (agreement == null || request.recalculateAgreement()) agreement = calculatedAgreement(purchase);
        if (!Objects.equals(purchase.partnerCustomerId(), agreement.partnerCustomerId()))
            throw new BusinessRuleException("De partner verschilt van de vastgelegde financieringsafspraak");
        Set<Long> seen = new HashSet<>();
        List<PartnerAdvanceSchedules.Row> replacement = new ArrayList<>();
        int position = 0;
        for (var input : request.rows()) {
            if (input == null) throw new BusinessRuleException("Een voorschottermijn mag niet leeg zijn");
            var previous = input.id() == null ? null : existing.get(input.id());
            if (input.id() != null && (previous == null || !seen.add(input.id())))
                throw new BusinessRuleException("De voorschottermijn hoort niet bij deze inkooporder of staat dubbel");
            String label = input.label() == null ? "" : input.label().strip();
            if (label.isEmpty() || label.length() > 160) throw new BusinessRuleException("Geef elke termijn een naam van maximaal 160 tekens");
            if ((input.percentage() == null) == (input.amountEur() == null))
                throw new BusinessRuleException("Geef per termijn een percentage of een bedrag op");
            BigDecimal pct = input.percentage();
            if (pct != null && (pct.signum() <= 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0 || pct.scale() > 4))
                throw new BusinessRuleException("Het termijnpercentage ligt boven 0 en maximaal op 100, met maximaal 4 decimalen");
            BigDecimal amount = pct == null ? Money.money(input.amountEur())
                    : agreement.agreedAmountEur().multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) throw new BusinessRuleException("Elke voorschottermijn is minstens € 0,01");
            if (previous != null && previous.invoiceId() != null && pct != null && same(previous.percentage(), pct))
                amount = previous.amountEur(); // An issued residual cent belongs to this exact invoice.
            replacement.add(new PartnerAdvanceSchedules.Row(input.id(), purchaseId, position++, label, pct, amount,
                    input.dueDate(), previous == null ? null : previous.invoiceId()));
        }
        for (var row : current) if (row.invoiceId() != null && !seen.contains(row.id()))
            throw new BusinessRuleException("Een termijn met een factuur moet in het betalingsplan behouden blijven");
        if (!replacement.isEmpty() && replacement.stream().allMatch(row -> row.percentage() != null)
                && replacement.stream().map(PartnerAdvanceSchedules.Row::percentage).reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(BigDecimal.valueOf(100)) == 0) {
            BigDecimal residual = agreement.agreedAmountEur().subtract(sumRows(replacement));
            if (residual.signum() != 0) {
                int lastEditable = -1;
                for (int i = 0; i < replacement.size(); i++) if (replacement.get(i).invoiceId() == null) lastEditable = i;
                if (lastEditable < 0) throw new BusinessRuleException("De gefactureerde termijnen sluiten niet aan op de afgesproken partnerbijdrage");
                var row = replacement.get(lastEditable);
                BigDecimal adjusted = row.amountEur().add(residual);
                if (adjusted.signum() <= 0) throw new BusinessRuleException("De laatste termijn moet minstens € 0,01 blijven na afronding");
                replacement.set(lastEditable, new PartnerAdvanceSchedules.Row(row.id(), row.purchaseOrderId(), row.position(),
                        row.label(), row.percentage(), adjusted, row.dueDate(), row.invoiceId()));
            }
        }
        for (var row : replacement) {
            var previous = row.id() == null ? null : existing.get(row.id());
            if (previous != null && previous.invoiceId() != null && (!previous.label().equals(row.label())
                    || !same(previous.percentage(), row.percentage()) || previous.amountEur().compareTo(row.amountEur()) != 0
                    || !Objects.equals(previous.dueDate(), row.dueDate())))
                throw new BusinessRuleException("Een termijn met een factuur staat vast; verwijder eerst de ongebruikte conceptfactuur om de termijn te wijzigen");
        }
        if (hasSettlement(purchaseId)) {
            for (var row : replacement) if (row.invoiceId() == null) {
                var previous = row.id() == null ? null : existing.get(row.id());
                if (previous == null || !previous.label().equals(row.label()) || !same(previous.percentage(), row.percentage())
                        || previous.amountEur().compareTo(row.amountEur()) != 0 || !Objects.equals(previous.dueDate(), row.dueDate()))
                    throw new BusinessRuleException("Na de eerste afrekening kunnen geen nieuwe voorschottermijnen meer worden toegevoegd of gewijzigd; ongebruikte termijnen kunnen wel worden verwijderd");
            }
        }
        BigDecimal outside = outsideReservations(purchaseId, replacement, null);
        requireWithin(agreement.agreedAmountEur(), sumRows(replacement).add(outside));
        schedules.save(agreement);
        for (var row : current) if (!seen.contains(row.id())) schedules.delete(row.id());
        for (var row : replacement) schedules.save(row);
        audit(purchase, "Voorschotplan vastgelegd: € " + sumRows(replacement) + " van partnerbijdrage € " + agreement.agreedAmountEur());
        return get(purchaseId);
    }

    @Transactional
    public SalesOrder createInvoice(long purchaseId, long rowId) {
        return createInvoice(purchaseId, rowId, null);
    }

    /** An explicit legacy source keeps historical links deterministic during migration. */
    @Transactional
    SalesOrder createInvoice(long purchaseId, long rowId, Long requestedSourceQuoteId) {
        PurchaseOrder purchase = purchases.lockForPartnerSettlement(purchaseId);
        var agreement = schedules.find(purchaseId);
        var row = schedules.rows(purchaseId).stream().filter(value -> value.id() == rowId).findFirst()
                .orElseThrow(() -> new NotFoundException("Voorschottermijn", rowId));
        if (row.invoiceId() != null && PartnerFinancingService.live(sales.get(row.invoiceId()))) {
            SalesOrder existing = sales.get(row.invoiceId());
            if (requestedSourceQuoteId != null && !requestedSourceQuoteId.equals(existing.sourceQuoteId()))
                throw new BusinessRuleException("Deze voorschottermijn hoort al bij een andere historische offerte");
            return existing;
        }
        if (agreement == null) throw new BusinessRuleException("Bewaar eerst het voorschotplan");
        if (!Objects.equals(purchase.partnerCustomerId(), agreement.partnerCustomerId()))
            throw new BusinessRuleException("De partner verschilt van de financieringsafspraak");
        var quotationIds = advanceQuotes.quoteIds(purchaseId);
        var planRows = schedules.rows(purchaseId);
        Long sourceQuoteId = (requestedSourceQuoteId == null ? quotationIds : List.of(requestedSourceQuoteId)).stream().filter(id -> {
            SalesOrder quote = sales.get(id);
            if (quote.isInvoice() || !quote.isPartnerAdvance() || !Objects.equals(quote.linkedPurchaseOrderId(), purchaseId)
                    || !PartnerFinancingService.live(quote) || !Objects.equals(quote.customerId(), purchase.partnerCustomerId())) return false;
            var snapshot = advanceQuotes.find(id);
            return snapshot != null && same(snapshot.sharePct(), purchase.partnerSharePctOrDefault())
                    && same(snapshot.financingPct(), agreement.financingPct())
                    && same(snapshot.agreedAmountEur(), agreement.agreedAmountEur())
                    && snapshot.rows().size() == planRows.size()
                    && planRows.stream().allMatch(planned -> snapshot.rows().stream().anyMatch(quoted ->
                        Objects.equals(quoted.scheduleRowId(), planned.id()) && quoted.label().equals(planned.label())
                        && same(quoted.percentage(), planned.percentage()) && same(quoted.amountEur(), planned.amountEur())
                        && Objects.equals(quoted.dueDate(), planned.dueDate())));
        }).findFirst().orElse(null);
        if (requestedSourceQuoteId != null && sourceQuoteId == null)
            throw new BusinessRuleException("De historische offerte stemt niet overeen met het actuele voorschotplan");
        String notes = sourceQuoteId == null ? null : sales.get(sourceQuoteId).notes();
        SalesOrder invoice = sales.createScheduledPartnerAdvance(purchase, row.id(), row.label(), row.amountEur(), row.dueDate(), notes, sourceQuoteId);
        schedules.save(new PartnerAdvanceSchedules.Row(row.id(), purchaseId, row.position(), row.label(), row.percentage(),
                row.amountEur(), row.dueDate(), invoice.id()));
        audit(purchase, "Voorschotfactuur " + invoice.number() + " aangemaakt voor " + row.label() + ": € " + row.amountEur());
        return invoice;
    }

    /** Every invoice draft reserves financing, including invoices created outside the schedule. */
    public void validateReservation(SalesOrder candidate, Long creatingRowId) {
        if (!candidate.isPartnerAdvance() || !candidate.isInvoice()) return;
        PurchaseOrder purchase = purchases.lockForPartnerSettlement(candidate.linkedPurchaseOrderId());
        if (hasSettlement(purchase.id()))
            throw new BusinessRuleException("Na de eerste afrekening kunnen geen nieuwe voorschotfacturen meer worden gemaakt of gewijzigd");
        var agreement = schedules.find(purchase.id());
        if (agreement == null) agreement = calculatedAgreement(purchase);
        if (!Objects.equals(candidate.customerId(), agreement.partnerCustomerId()))
            throw new BusinessRuleException("Het voorschot hoort bij een andere partner dan de financieringsafspraak");
        var rows = schedules.rows(purchase.id());
        var linkedRow = candidate.id() == null ? null : schedules.forInvoice(candidate.id());
        var reservedRow = creatingRowId == null ? linkedRow : rows.stream().filter(row -> row.id().equals(creatingRowId)).findFirst().orElse(null);
        BigDecimal amount = Money.money(sales.price(candidate).totals().total());
        if (amount.signum() <= 0) throw new BusinessRuleException("Een voorschotfactuur heeft een bedrag groter dan nul");
        if (reservedRow != null && (reservedRow.amountEur().compareTo(amount) != 0 || candidate.paymentPlan() != SalesPaymentPlan.FULL))
            throw new BusinessRuleException("Het bedrag van een termijnfactuur staat vast en wordt niet opnieuw in betaaltermijnen gesplitst");
        if (creatingRowId != null && reservedRow == null) throw new BusinessRuleException("Onbekende voorschottermijn");
        BigDecimal outside = outsideReservations(purchase.id(), rows, candidate.id());
        BigDecimal total = sumRows(rows).add(outside).add(reservedRow == null ? amount : ZERO);
        requireWithin(agreement.agreedAmountEur(), total);
        if (schedules.find(purchase.id()) == null) schedules.save(agreement);
    }

    public void detachInvoice(long invoiceId) { schedules.detachInvoice(invoiceId); }
    public void requireReadyForSettlement(long purchaseId) {
        if (schedules.rows(purchaseId).stream().anyMatch(row -> row.invoiceId() == null))
            throw new BusinessRuleException("Maak en reik eerst de geplande voorschotfacturen uit, of verwijder de ongebruikte termijnen, voordat je een afrekening maakt");
        if (advanceInvoices(purchaseId).stream().anyMatch(order -> order.status() == QuoteStatus.CONCEPT))
            throw new BusinessRuleException("Reik eerst de voorschotconcepten uit, of verwijder ongebruikte conceptfacturen en termijnen, voordat je een afrekening maakt");
    }
    public BigDecimal agreedAmount(long purchaseId) {
        var saved = schedules.find(purchaseId);
        return saved == null ? calculatedAgreement(purchases.get(purchaseId)).agreedAmountEur() : saved.agreedAmountEur();
    }

    private PartnerAdvanceSchedules.Agreement calculatedAgreement(PurchaseOrder purchase) {
        if (purchase.partnerCustomerId() == null) throw new BusinessRuleException("Koppel eerst een partner aan de inkooporder");
        BigDecimal external = Money.money(purchases.reconciliation(purchase.id()).totals().forecastExternalEur());
        BigDecimal pct = purchase.partnerCostPctOrDefault();
        return new PartnerAdvanceSchedules.Agreement(purchase.id(), purchase.partnerCustomerId(), external, pct,
                external.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
    }
    private List<SalesOrder> advanceInvoices(long purchaseId) {
        return orders.findAll().stream().filter(order -> order.isInvoice() && order.isPartnerAdvance()
                && Objects.equals(order.linkedPurchaseOrderId(), purchaseId) && PartnerFinancingService.live(order)).toList();
    }
    private boolean hasSettlement(long purchaseId) {
        return orders.findAll().stream().anyMatch(order -> order.isInvoice() && order.purpose() == SalesPurpose.PARTNER_SETTLEMENT
                && Objects.equals(order.linkedPurchaseOrderId(), purchaseId) && PartnerFinancingService.live(order));
    }
    private BigDecimal outsideReservations(long purchaseId, List<PartnerAdvanceSchedules.Row> rows, Long excludeInvoice) {
        Set<Long> scheduledIds = rows.stream().map(PartnerAdvanceSchedules.Row::invoiceId).filter(Objects::nonNull).collect(Collectors.toSet());
        return Money.money(advanceInvoices(purchaseId).stream().filter(order -> !scheduledIds.contains(order.id())
                && !Objects.equals(excludeInvoice, order.id())).map(order -> sales.price(order).totals().total()).reduce(ZERO, BigDecimal::add));
    }
    private static BigDecimal sumRows(List<PartnerAdvanceSchedules.Row> rows) {
        return Money.money(rows.stream().map(PartnerAdvanceSchedules.Row::amountEur).reduce(ZERO, BigDecimal::add));
    }
    private static void requireWithin(BigDecimal agreement, BigDecimal reserved) {
        if (reserved.compareTo(agreement) > 0)
            throw new BusinessRuleException("Termijnen en bestaande voorschotfacturen reserveren € " + reserved
                    + ", meer dan de afgesproken partnerbijdrage van € " + agreement);
    }
    private static boolean same(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private static String plain(BigDecimal amount) { return amount.stripTrailingZeros().toPlainString(); }
    private void audit(PurchaseOrder purchase, String message) {
        if (activity != null && activity.isResolvable()) activity.get().record(ActivityLogService.ACTION_UPDATED,
                "PURCHASE_ORDER", purchase.id().toString(), purchase.number(), message);
    }
}
