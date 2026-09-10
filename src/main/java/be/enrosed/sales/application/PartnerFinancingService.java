package be.enrosed.sales.application;

import be.enrosed.sales.domain.*;
import be.enrosed.shared.Money;
import be.enrosed.sourcing.application.PurchaseOrderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

@ApplicationScoped
public class PartnerFinancingService {
    @Inject PurchaseOrderService purchases;
    @Inject SalesOrderService sales;
    @Inject CustomerService customers;
    @Inject IncomingPaymentService incoming;
    @Inject PartnerSettlements settlements;
    @Inject PartnerAdvanceScheduleService schedules;
    @Inject PartnerAdvanceSchedules advanceRows;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public record Document(Long id, String number, SalesPurpose purpose, DocumentType docType, QuoteStatus status,
                           BigDecimal invoiceTotalEur, BigDecimal receivedEur, BigDecimal remainingEur, BigDecimal creditEur) {}
    public record Summary(long purchaseOrderId, Long partnerCustomerId, String partnerName,
                          BigDecimal costPct, BigDecimal profitSharePct, SalesPaymentPlan paymentPlan,
                          BigDecimal plannedExternalEur, BigDecimal forecastExternalEur, boolean costFinalized,
                          BigDecimal committedAdvanceEur, BigDecimal invoicedAdvanceEur, BigDecimal receivedAdvanceEur,
                          BigDecimal openAdvanceEur, Long settlementInvoiceId, String settlementInvoiceNumber,
                          BigDecimal settlementEur, BigDecimal receivedSettlementEur, BigDecimal openSettlementEur,
                          BigDecimal creditEur, BigDecimal totalReceivedEur, BigDecimal totalOpenEur,
                          BigDecimal ownExposureEur, BigDecimal recognizedRevenueEur, BigDecimal recognizedCostEur,
                          BigDecimal recognizedProfitEur, List<Document> documents,
                          List<IncomingPaymentService.IncomingPayment> payments,
                          boolean settlementComplete, int settledQuantity, int remainingQuantity,
                          BigDecimal unbilledAdvanceEur, int unbilledAdvanceCount,
                          BigDecimal overdueUnbilledAdvanceEur, LocalDate nextAdvanceDueDate,
                          BigDecimal financingBasisEur, PartnerAdvanceBasis.Kind financingBasis) {}

    public PartnerSettlements.Snapshot settlement(SalesOrder order) {
        return order.id() != null && order.purpose() == SalesPurpose.PARTNER_SETTLEMENT ? settlements.find(order.id()) : null;
    }

    public SalesAccounting accounting(SalesOrder order, PricedOrder priced) {
        if (!issued(order) || order.isPartnerAdvance()) return new SalesAccounting(ZERO, ZERO, ZERO, 0);
        BigDecimal revenue = Money.money(priced.totals().total());
        BigDecimal cost = Money.money(priced.totals().costTotal());
        if (order.purpose() == SalesPurpose.PARTNER_SETTLEMENT) {
            var snapshot = order.id() == null ? null : settlements.find(order.id());
            if (snapshot != null) { revenue = snapshot.revenueEur(); cost = snapshot.costEur(); }
            else {
                // Legacy final invoices credited the advance on a named extra line.
                BigDecimal credited = order.extraLines().stream().filter(line -> line.description() != null
                                && line.description().startsWith("Voorschot verrekend"))
                        .map(SalesExtraLine::total).reduce(ZERO, BigDecimal::add).negate().max(ZERO);
                revenue = revenue.add(credited);
                cost = cost.add(credited);
            }
        }
        return new SalesAccounting(revenue, cost, Money.money(revenue.subtract(cost)), priced.totals().pieces());
    }

    public List<Summary> list() {
        return purchases.list().stream().filter(order -> order.partnerCustomerId() != null).map(order -> get(order.id())).toList();
    }

    public Summary get(long id) {
        var purchase = purchases.get(id);
        var reconciliation = purchases.reconciliation(id);
        var docs = sales.list().stream().filter(order -> order.isPartnerDeal()
                && Long.valueOf(id).equals(order.linkedPurchaseOrderId())).toList();
        List<Document> documents = docs.stream().map(order -> {
            var summary = incoming.summary(order, sales.price(order));
            return new Document(order.id(), order.number(), order.purpose(), order.docType(), order.status(),
                    summary.invoiceTotalEur(), summary.receivedEur(), summary.remainingEur(), summary.creditEur());
        }).toList();
        var advances = docs.stream().filter(order -> order.isPartnerAdvance() && issued(order)).toList();
        var finals = docs.stream().filter(order -> order.purpose() == SalesPurpose.PARTNER_SETTLEMENT && order.isInvoice() && live(order)).toList();
        var finalInvoice = finals.stream().filter(order -> {
            var snapshot = settlements.find(order.id());
            return snapshot == null || snapshot.finalSettlement();
        }).findFirst().orElse(null);
        BigDecimal invoiced = sum(advances, order -> sales.price(order).totals().total());
        BigDecimal receivedAdvance = sum(docs.stream().filter(SalesOrder::isPartnerAdvance).toList(), order -> incoming.summary(order, sales.price(order)).receivedEur());
        BigDecimal openAdvance = sum(advances, order -> incoming.summary(order, sales.price(order)).remainingEur());
        BigDecimal finalAmount = sum(finals, order -> sales.price(order).totals().total());
        BigDecimal receivedFinal = sum(docs.stream().filter(order -> order.purpose() == SalesPurpose.PARTNER_SETTLEMENT).toList(), order -> incoming.summary(order, sales.price(order)).receivedEur());
        BigDecimal openFinal = sum(finals.stream().filter(PartnerFinancingService::issued).toList(),
                order -> incoming.summary(order, sales.price(order)).remainingEur());
        BigDecimal credit = sum(docs.stream().filter(PartnerFinancingService::issued).toList(),
                order -> incoming.summary(order, sales.price(order)).refundableEur());
        BigDecimal totalReceived = receivedAdvance.add(receivedFinal);
        List<IncomingPaymentService.IncomingPayment> payments = docs.stream().flatMap(order -> incoming.forOrder(order.id()).stream()
                .map(payment -> incoming.enrich(payment, order))).toList();
        BigDecimal revenue = sum(docs, order -> accounting(order, sales.price(order)).recognizedRevenueEur());
        BigDecimal cost = sum(docs, order -> accounting(order, sales.price(order)).recognizedCostEur());
        String name = purchase.partnerCustomerId() == null ? null : customers.get(purchase.partnerCustomerId()).company();
        var availability = sales.partnerSettlementAvailability(id);
        int remainingQuantity = availability.lines().stream().mapToInt(PartnerSettlementLedger.Line::remainingQuantity).sum();
        int settledQuantity = availability.lines().stream().mapToInt(PartnerSettlementLedger.Line::settledQuantity).sum();
        boolean settlementComplete = !availability.settlements().isEmpty() && remainingQuantity == 0
                && availability.settlements().stream().noneMatch(document -> document.status() == QuoteStatus.CONCEPT);
        List<PartnerAdvanceSchedules.Row> pending = finals.isEmpty()
                ? advanceRows.rows(id).stream().filter(row -> row.invoiceId() == null).toList() : List.of();
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Brussels"));
        BigDecimal unbilled = Money.money(pending.stream().map(PartnerAdvanceSchedules.Row::amountEur).reduce(ZERO, BigDecimal::add));
        BigDecimal overdue = Money.money(pending.stream().filter(row -> row.dueDate() != null && !row.dueDate().isAfter(today))
                .map(PartnerAdvanceSchedules.Row::amountEur).reduce(ZERO, BigDecimal::add));
        LocalDate nextDue = pending.stream().map(PartnerAdvanceSchedules.Row::dueDate).filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        var agreement = purchase.partnerCustomerId() == null ? null : schedules.get(id);
        return new Summary(id, purchase.partnerCustomerId(), name, purchase.partnerCostPctOrDefault(),
                purchase.partnerSharePctOrDefault(), docs.stream().filter(SalesOrder::isPartnerAdvance).findFirst()
                .map(SalesOrder::paymentPlan).orElse(SalesPaymentPlan.THIRD_TWO_THIRDS_PRODUCTION),
                reconciliation.totals().plannedExternalEur(), reconciliation.totals().forecastExternalEur(), reconciliation.totals().finalized(),
                purchase.partnerCustomerId() == null ? ZERO : schedules.agreedAmount(id),
                invoiced, receivedAdvance, openAdvance, finalInvoice == null ? null : finalInvoice.id(),
                finalInvoice == null ? null : finalInvoice.number(), finalAmount, receivedFinal, openFinal,
                credit, totalReceived, openAdvance.add(openFinal), reconciliation.totals().paidEur().subtract(totalReceived).max(ZERO),
                revenue, cost, revenue.subtract(cost), documents, payments, settlementComplete, settledQuantity, remainingQuantity,
                unbilled, pending.size(), overdue, nextDue,
                agreement == null ? PartnerAdvanceBasis.total(purchases.calculate(purchase)) : agreement.financingBasisEur(),
                agreement == null ? PartnerAdvanceBasis.Kind.PURCHASE_TOTAL_WITH_SEPARATE_COSTS : agreement.financingBasis());
    }

    public static boolean issued(SalesOrder order) { return order.isInvoice() && order.status() != QuoteStatus.CONCEPT && live(order); }
    public static boolean live(SalesOrder order) {
        return order.status() != QuoteStatus.GEANNULEERD && order.status() != QuoteStatus.AFGEWEZEN && order.status() != QuoteStatus.VERLOPEN;
    }
    private BigDecimal sum(List<SalesOrder> rows, Function<SalesOrder, BigDecimal> amount) {
        return Money.money(rows.stream().map(amount).reduce(ZERO, BigDecimal::add));
    }
}
