package be.enrosed.sourcing.application;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PriceBasis;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static be.enrosed.sourcing.domain.PurchasePayment.Payee.*;
import static be.enrosed.sourcing.domain.PurchaseReconciliation.Status.*;
import static be.enrosed.sourcing.domain.PurchaseReconciliation.UnitCostBasis.*;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseReconciliationCalculatorTest {
    private final PurchaseReconciliationCalculator calculator = new PurchaseReconciliationCalculator();

    @Test
    void noPaymentsRetainsTheWholeBudgetWithoutInventingSavings() {
        var result = calculate(budget("1000", "200", "50", "100"), List.of());
        eq("1250.00", result.totals().plannedExternalEur());
        eq("0.00", result.totals().paidEur());
        eq("1250.00", result.totals().remainingEur());
        eq("1250.00", result.totals().forecastExternalEur());
        eq("0.00", result.totals().varianceEur());
        eq("1350.00", result.totals().forecastPricingEur());
        assertFalse(result.totals().finalized());
        assertEquals(UNPAID, stream(result, SUPPLIER).status());
    }

    @Test
    void conceptWithoutPaymentsIsShownAsPlanned() {
        var budget = budget("1000", "0", "0", "0");
        var concept = budget.order.withReceipt(PurchaseOrderStatus.CONCEPT, null, null, false, null, budget.order.lines());
        var result = calculator.calculate(concept, budget.costing, budget.payable, List.of());
        assertEquals(PLANNED, stream(result, SUPPLIER).status());
        assertFalse(result.totals().finalized());
        eq("0.00", result.totals().varianceEur());
    }

    @Test
    void aPartialPaymentDoesNotLowerTheProjectedCost() {
        var result = calculate(budget("1000", "200", "0", "0"), List.of(payment(SUPPLIER, "350", false)));
        assertEquals(PARTIAL, stream(result, SUPPLIER).status());
        eq("650.00", stream(result, SUPPLIER).remainingEur());
        eq("1000.00", stream(result, SUPPLIER).forecastEur());
        eq("0.00", stream(result, SUPPLIER).settledSavingEur());
        eq("1200.00", result.totals().forecastExternalEur());
    }

    @Test
    void explicitlySettledUnderpaymentIsTheConfirmedLowerCost() {
        var result = calculate(budget("1000", "0", "0", "100"), List.of(
                payment(SUPPLIER, "300", false), payment(SUPPLIER, "640", true)));
        var supplier = stream(result, SUPPLIER);
        assertEquals(SETTLED_LOWER, supplier.status());
        assertTrue(supplier.explicitlySettled());
        assertTrue(supplier.finalized());
        eq("0.00", supplier.remainingEur());
        eq("60.00", supplier.settledSavingEur());
        eq("-60.00", result.totals().varianceEur());
        eq("940.00", result.totals().forecastExternalEur());
        eq("1040.00", result.totals().forecastPricingEur());
        assertTrue(result.totals().finalized());
    }

    @Test
    void noImplicitTenEuroToleranceChangesTheCostOrClearsTheBalance() {
        var result = calculate(budget("1000", "0", "0", "0"), List.of(payment(SUPPLIER, "995", false)));
        eq("5.00", stream(result, SUPPLIER).remainingEur());
        eq("0.00", stream(result, SUPPLIER).settledSavingEur());
        eq("1000.00", result.totals().forecastExternalEur());
        assertFalse(result.totals().finalized());
    }

    @Test
    void aPaidExactBudgetIsCompleteWithoutAnExplicitFinalMarker() {
        var result = calculate(budget("1000", "200", "50", "100"), List.of(
                payment(SUPPLIER, "1000", false), payment(LOGISTICS, "200", false), payment(SEPARATE, "50", false)));
        assertTrue(result.totals().finalized());
        assertEquals(PAID, stream(result, SUPPLIER).status());
        eq("0.00", result.totals().remainingEur());
        eq("1250.00", result.totals().paidEur());
        eq("100.00", result.totals().internalMarkupEur());
    }

    @Test
    void overpaymentIncreasesForecastButRemainsProvisionalUntilSettled() {
        var result = calculate(budget("1000", "0", "0", "0"), List.of(payment(SUPPLIER, "1075", false)));
        var supplier = stream(result, SUPPLIER);
        assertEquals(OVERPAID, supplier.status());
        eq("75.00", supplier.overpaidEur());
        eq("0.00", supplier.remainingEur());
        eq("1075.00", result.totals().forecastExternalEur());
        eq("75.00", result.totals().varianceEur());
        assertFalse(supplier.finalized());
        assertFalse(result.totals().finalized());
    }

    @Test
    void explicitlySettledOverpaymentIsCompleteAndStillVisible() {
        var result = calculate(budget("1000", "0", "0", "0"), List.of(payment(SUPPLIER, "1075", true)));
        eq("75.00", stream(result, SUPPLIER).overpaidEur());
        assertEquals(OVERPAID, stream(result, SUPPLIER).status());
        assertTrue(result.totals().finalized());
    }

    @Test
    void supplierSurplusCannotCancelAnUnpaidCustomsStream() {
        var result = calculate(budget("1000", "100", "0", "0"), List.of(payment(SUPPLIER, "1100", true)));
        eq("100.00", stream(result, LOGISTICS).remainingEur());
        eq("100.00", result.totals().remainingEur());
        eq("1200.00", result.totals().forecastExternalEur());
        eq("100.00", result.totals().varianceEur());
        assertFalse(result.totals().finalized());
    }

    @Test
    void unbudgetedBankOrCourierFeesAreIncludedOnceAsAdditionalCosts() {
        var result = calculate(budget("1000", "200", "0", "0"), List.of(
                payment(SUPPLIER, "1000", false), payment(LOGISTICS, "200", false), payment(OTHER, "17.95", false)));
        var other = stream(result, OTHER);
        assertEquals(ADDITIONAL, other.status());
        eq("0.00", other.plannedEur());
        eq("17.95", other.forecastEur());
        eq("0.00", other.overpaidEur());
        assertTrue(other.finalized());
        eq("1217.95", result.totals().forecastExternalEur());
        eq("17.95", result.totals().varianceEur());
        assertTrue(result.totals().finalized());
        assertConserved(result);
    }

    @Test
    void emptyStreamsAreNotApplicableAndDoNotPreventCompletion() {
        var result = calculate(budget("0", "0", "0", "0"), List.of());
        assertTrue(result.totals().finalized());
        assertTrue(result.streams().stream().allMatch(stream -> stream.status() == NOT_APPLICABLE));
    }

    @Test
    void anEmptyDraftDoesNotClaimToBeAFinalContainerSettlement() {
        var empty = order(List.of()).withReceipt(PurchaseOrderStatus.CONCEPT, null, null, false, null, List.of());
        var result = calculate(fixture(empty, "0", "0", "0", "0", false, List.of()), List.of());
        assertFalse(result.totals().finalized());
        assertNull(result.totals().forecastExternalUnitEur());
    }

    @Test
    void euroAmountsArePinnedAndNullLegacyPayeeMeansSupplier() {
        var payment = new PurchasePayment(1L, 1L, LocalDate.of(2026, 1, 1), bd("99999"), Currency.CNY,
                bd("87.65"), "Historical transfer", null, null, null, true);
        var result = calculate(budget("100", "0", "0", "0"), List.of(payment));
        eq("87.65", result.totals().paidEur());
        eq("-12.35", result.totals().varianceEur());
        assertEquals(1, stream(result, SUPPLIER).paymentCount());
    }

    @Test
    void theHistoricalReceiptHeaderDoesNotDoubleCountTheLedger() {
        var budget = budget("100", "0", "0", "0");
        var received = budget.order.withReceipt(PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 1, 10),
                bd("100"), true, null, budget.order.lines());
        var result = calculator.calculate(received, budget.costing, budget.payable, List.of(payment(SUPPLIER, "100", true)));
        eq("100.00", result.totals().paidEur());
        eq("100.00", result.totals().legacyPaidTotalEur());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("historische totaal")));
    }

    @Test
    void anUnallocatedHistoricalHeaderDoesNotMasqueradeAsSupplierPayment() {
        var budget = budget("100", "0", "0", "0");
        var received = budget.order.withReceipt(PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 1, 10),
                bd("100"), true, null, budget.order.lines());
        var result = calculator.calculate(received, budget.costing, budget.payable, List.of());
        eq("0.00", result.totals().paidEur());
        eq("100.00", result.totals().remainingEur());
        assertFalse(result.totals().finalized());
    }

    @Test
    void missingLegacyEuroValueIsFlaggedAndCannotFinalizeTheStream() {
        var unknown = new PurchasePayment(1L, 1L, LocalDate.of(2026, 1, 1), bd("100"), Currency.USD,
                null, "Legacy transfer", null, null, SUPPLIER, true);
        var result = calculate(budget("100", "0", "0", "0"), List.of(unknown));
        assertFalse(result.totals().finalized());
        eq("100.00", result.totals().remainingEur());
        eq("0.00", stream(result, SUPPLIER).settledSavingEur());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("mist de vastgelegde eurowaarde")));
    }

    @Test
    void inspectionInsideAndOutsidePiecePriceHaveTheSameContainerBudget() {
        var outside = budget("100", "20", "9", "5");
        var inside = fixture(outside.order, "100", "20", "9", "5", true,
                List.of(cost(1, 10, "100", "20", "9", "5")));
        var a = calculate(outside, List.of(payment(SEPARATE, "12", true)));
        var b = calculate(inside, List.of(payment(SEPARATE, "12", true)));
        eq("129.00", a.totals().plannedExternalEur());
        eq("132.00", a.totals().forecastExternalEur());
        assertEquals(a.totals(), b.totals());
        assertConserved(a);
        assertConserved(b);
    }

    @Test
    void eachCostStreamUsesItsOwnEstablishedProductWeights() {
        var order = order(List.of(line(1, 10, 10, 0, false), line(2, 10, 10, 0, false)));
        var budget = fixture(order, "100", "100", "100", "20", true, List.of(
                cost(1, 10, "75", "20", "10", "5"),
                cost(2, 10, "25", "80", "90", "15")));
        var result = calculate(budget, List.of(payment(SUPPLIER, "200", true),
                payment(LOGISTICS, "150", true), payment(SEPARATE, "200", true), payment(OTHER, "100", true)));
        var first = result.lines().getFirst();
        // 200*75% + 150*20% + 200*10% + 100*75% = 275; internal 5 separately.
        eq("275.00", first.forecastExternalEur());
        eq("5.00", first.internalMarkupEur());
        eq("280.00", first.forecastPricingEur());
        assertConserved(result);
    }

    @Test
    void manuallyAssignedNegativeProductMarkupIsPreservedWithoutRedistribution() {
        var order = order(List.of(line(1, 10, 10, 0, false).withExtraShare(bd("-100")),
                line(2, 10, 10, 0, false).withExtraShare(bd("200"))), Allocation.MANUAL);
        var budget = fixture(order, "200", "0", "0", "100", false, List.of(
                cost(1, 10, "100", "0", "0", "-100"), cost(2, 10, "100", "0", "0", "200")));
        var result = calculate(budget, List.of(payment(SUPPLIER, "200", true)));
        eq("-100.00", result.lines().get(0).internalMarkupEur());
        eq("200.00", result.lines().get(1).internalMarkupEur());
        eq("0.00", result.lines().get(0).forecastPricingEur());
        eq("300.00", result.lines().get(1).forecastPricingEur());
        eq("100.00", result.totals().internalMarkupEur());
        assertConserved(result);
    }

    @Test
    void offsettingManualProductMarkupSurvivesAZeroContainerMarkupTotal() {
        var order = order(List.of(line(1, 10, 10, 0, false).withExtraShare(bd("-100")),
                line(2, 10, 10, 0, false).withExtraShare(bd("100"))), Allocation.MANUAL);
        var budget = fixture(order, "200", "0", "0", "0", false, List.of(
                cost(1, 10, "100", "0", "0", "-100"), cost(2, 10, "100", "0", "0", "100")));
        var result = calculate(budget, List.of());
        eq("-100.00", result.lines().get(0).internalMarkupEur());
        eq("100.00", result.lines().get(1).internalMarkupEur());
        eq("0.00", result.totals().internalMarkupEur());
        assertConserved(result);
    }

    @Test
    void ddpCostsAlreadyPaidToSupplierDoNotInventLogisticsDebt() {
        var ddp = order(List.of(line(1, 10, 10, 0, true)));
        var budget = fixture(ddp, "100", "0", "10", "5", false, List.of(cost(1, 10, "100", "0", "0", "5")));
        var result = calculate(budget, List.of(payment(SUPPLIER, "100", true), payment(SEPARATE, "10", true)));
        assertEquals(NOT_APPLICABLE, stream(result, LOGISTICS).status());
        eq("110.00", result.totals().forecastExternalEur());
        assertTrue(result.totals().finalized());
        assertConserved(result);
    }

    @Test
    void unexpectedLogisticsPaymentsPreferNonDdpProductsWhenNoCostWeightsExist() {
        var order = order(List.of(line(1, 10, 10, 0, true), line(2, 10, 10, 0, false)));
        var budget = fixture(order, "100", "0", "0", "0", false, List.of(
                cost(1, 10, "50", "0", "0", "0"), cost(2, 10, "50", "0", "0", "0")));
        var result = calculate(budget, List.of(payment(LOGISTICS, "10", true)));
        eq("50.00", result.lines().get(0).forecastExternalEur());
        eq("60.00", result.lines().get(1).forecastExternalEur());
        assertConserved(result);
    }

    @Test
    void shortagesAndDamageRaiseCostPerUsablePieceWithoutShrinkingBudget() {
        var order = order(List.of(line(1, 80, 100, 10, false)));
        var received = order.withReceipt(PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 1, 10), null, true, null, order.lines());
        var budget = fixture(received, "1000", "200", "60", "140", false,
                List.of(cost(1, 100, "1000", "200", "0", "140")));
        var result = calculate(budget, List.of(payment(SUPPLIER, "1000", true),
                payment(LOGISTICS, "200", true), payment(SEPARATE, "60", true)));
        assertEquals(100, result.totals().orderedQuantity());
        assertEquals(80, result.totals().receivedQuantity());
        assertEquals(10, result.totals().damagedQuantity());
        assertEquals(70, result.totals().usableQuantity());
        assertEquals(70, result.totals().unitCostQuantity());
        assertEquals(USABLE_RECEIVED, result.totals().unitCostBasis());
        eq("1260.00", result.totals().plannedExternalEur());
        eq("18.0000", result.totals().forecastExternalUnitEur());
        eq("20.0000", result.lines().getFirst().forecastPricingUnitEur());
    }

    @Test
    void beforeReceiptUnitsUseOrderedQuantityAndDoNotPretendGoodsHaveArrived() {
        var result = calculate(budget("100", "0", "0", "0"), List.of());
        assertFalse(result.totals().receiptRecorded());
        assertEquals(ORDERED, result.totals().unitCostBasis());
        assertEquals(10, result.totals().unitCostQuantity());
        assertEquals(0, result.totals().receivedQuantity());
        assertEquals(0, result.totals().usableQuantity());
        eq("10.0000", result.totals().forecastExternalUnitEur());
    }

    @Test
    void zeroQuantitiesKeepAllCostsVisibleWithoutInventingAUnitPrice() {
        var order = order(List.of(line(1, 0, 0, 0, false), line(2, 0, 0, 0, false)));
        var budget = fixture(order, "0", "0", "0", "0", false, List.of(
                cost(1, 0, "0", "0", "0", "0"), cost(2, 0, "0", "0", "0", "0")));
        var result = calculate(budget, List.of(payment(OTHER, "0.03", true)));
        eq("0.02", result.lines().get(0).forecastExternalEur());
        eq("0.01", result.lines().get(1).forecastExternalEur());
        assertNull(result.totals().forecastExternalUnitEur());
        assertNull(result.lines().getFirst().forecastExternalUnitEur());
        assertConserved(result);
    }

    @Test
    void allDamagedPiecesHaveNoUsableUnitPriceAndStillKeepContainerCosts() {
        var order = order(List.of(line(1, 10, 10, 10, false)));
        var received = order.withReceipt(PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 1, 10), null, true, null, order.lines());
        var budget = fixture(received, "100", "0", "0", "0", false, List.of(cost(1, 10, "100", "0", "0", "0")));
        var result = calculate(budget, List.of(payment(SUPPLIER, "100", true)));
        assertEquals(0, result.totals().unitCostQuantity());
        eq("100.00", result.totals().forecastExternalEur());
        assertNull(result.totals().forecastExternalUnitEur());
        assertConserved(result);
    }

    @Test
    void noGoodsValueFallsBackToOrderedPiecesForExtraPayments() {
        var order = order(List.of(line(1, 1, 1, 0, false), line(2, 3, 3, 0, false)));
        var budget = fixture(order, "0", "0", "0", "0", false, List.of(
                cost(1, 1, "0", "0", "0", "0"), cost(2, 3, "0", "0", "0", "0")));
        var result = calculate(budget, List.of(payment(OTHER, "10", true)));
        eq("2.50", result.lines().get(0).paidEur());
        eq("7.50", result.lines().get(1).paidEur());
        assertConserved(result);
    }

    @Test
    void firstInspectionPaymentUsesTheChosenPieceKeyEvenWithoutAPriorInspectionBudget() {
        var order = order(List.of(line(1, 1, 1, 0, false), line(2, 3, 3, 0, false)))
                .withSeparateAllocation(Allocation.PIECES);
        var budget = fixture(order, "100", "0", "0", "0", true, List.of(
                cost(1, 1, "50", "0", "0", "0"), cost(2, 3, "50", "0", "0", "0")));
        var result = calculate(budget, List.of(payment(SEPARATE, "10", true)));
        eq("2.50", result.lines().get(0).paidEur());
        eq("7.50", result.lines().get(1).paidEur());
        assertConserved(result);
    }

    @Test
    void partialCentPaymentsCannotInventPerProductCostVariance() {
        var order = order(List.of(line(1, 1, 1, 0, false), line(2, 1, 1, 0, false)));
        var budget = fixture(order, "0.02", "0", "0", "0", false, List.of(
                cost(1, 1, "0.01", "0", "0", "0"), cost(2, 1, "0.01", "0", "0", "0")));
        var result = calculate(budget, List.of(payment(SUPPLIER, "0.01", false)));
        for (var line : result.lines()) {
            eq("0.01", line.plannedExternalEur());
            eq("0.01", line.forecastExternalEur());
            eq("0.00", line.varianceEur());
            assertTrue(line.remainingEur().signum() >= 0);
        }
        eq("0.01", result.lines().get(0).paidEur());
        eq("0.00", result.lines().get(0).remainingEur());
        eq("0.00", result.lines().get(1).paidEur());
        eq("0.01", result.lines().get(1).remainingEur());
        assertConserved(result);
    }

    @Test
    void duplicateProductRowsAggregateReceiptCountsAndPreserveEveryCent() {
        var order = order(List.of(line(1, 4, 5, 1, false), line(1, 3, 5, 0, false)));
        var received = order.withReceipt(PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 1, 10), null, true, null, order.lines());
        var budget = fixture(received, "100", "10", "0", "0", false, List.of(
                cost(1, 5, "50", "5", "0", "0"), cost(1, 5, "50", "5", "0", "0")));
        var result = calculate(budget, List.of(payment(SUPPLIER, "101", true)));
        assertEquals(1, result.lines().size());
        assertEquals(10, result.lines().getFirst().orderedQuantity());
        assertEquals(7, result.lines().getFirst().receivedQuantity());
        assertEquals(6, result.lines().getFirst().usableQuantity());
        assertConserved(result);
    }

    @Test
    void centConservationHoldsAcrossUnevenWeightsPartialPaymentsAndMarkup() {
        Random random = new Random(728911L);
        var order = order(List.of(line(1, 1, 1, 0, false), line(2, 2, 2, 0, false), line(3, 7, 7, 0, false)));
        for (int iteration = 0; iteration < 250; iteration++) {
            var budget = fixture(order, amount(random), amount(random), amount(random), amount(random), true, List.of(
                    cost(1, 1, amount(random), amount(random), amount(random), amount(random)),
                    cost(2, 2, amount(random), amount(random), amount(random), amount(random)),
                    cost(3, 7, amount(random), amount(random), amount(random), amount(random))));
            var result = calculate(budget, List.of(payment(SUPPLIER, amount(random), random.nextBoolean()),
                    payment(LOGISTICS, amount(random), random.nextBoolean()), payment(SEPARATE, amount(random), random.nextBoolean()),
                    payment(OTHER, amount(random), random.nextBoolean())));
            assertConserved(result);
            assertTrue(result.lines().stream().allMatch(line -> line.remainingEur().signum() >= 0));
        }
    }

    @Test
    void missingProductCalculationAndMissingHistoricalQuantityAreDisclosed() {
        var legacy = new PurchaseOrderLine(1L, 1L, 10, bd("10"), Currency.EUR, null, null);
        var order = order(List.of(legacy));
        var received = order.withReceipt(PurchaseOrderStatus.ONTVANGEN, LocalDate.of(2026, 1, 10), null, true, null, order.lines());
        var budget = fixture(received, "100", "0", "0", "0", false, List.of());
        var result = calculate(budget, List.of());
        assertEquals(10, result.totals().orderedQuantity());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("oorspronkelijke bestelde aantal")));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("ontbreekt in de oorspronkelijke kostberekening")));
        assertConserved(result);
    }

    private PurchaseReconciliation calculate(Fixture fixture, List<PurchasePayment> payments) {
        return calculator.calculate(fixture.order, fixture.costing, fixture.payable, payments);
    }

    private Fixture budget(String supplier, String logistics, String separate, String markup) {
        var order = order(List.of(line(1, 10, 10, 0, false)));
        return fixture(order, supplier, logistics, separate, markup, false,
                List.of(cost(1, 10, supplier, logistics, "0", markup)));
    }

    private Fixture fixture(PurchaseOrder order, String supplier, String logistics, String separate,
                            String markup, boolean separateInside, List<LandedCost.Line> lines) {
        BigDecimal external = bd(supplier).add(bd(logistics));
        BigDecimal total = external.add(bd(markup)).add(separateInside ? bd(separate) : bd("0"));
        var totals = new LandedCost.Totals(order.lines().stream().mapToInt(PurchaseOrderLine::ordered).sum(),
                0, bd("0"), bd(supplier), bd(supplier), bd("0"), bd(logistics), bd(supplier), bd("0"),
                bd("0"), bd(markup), total, bd("0"), bd("0"), bd(separate), List.of(), bd("0"), bd(separate),
                external.add(bd(markup)).add(bd(separate)), separateInside);
        boolean ddp = order.lines().stream().allMatch(PurchaseOrderLine::deliveredDutyPaid);
        return new Fixture(order, new LandedCost(lines, totals, null),
                new PurchaseOrderService.Payable(bd(supplier), bd(logistics), bd(markup), ddp, ddp));
    }

    private PurchaseOrder order(List<PurchaseOrderLine> lines) {
        return order(lines, Allocation.PIECES);
    }

    private PurchaseOrder order(List<PurchaseOrderLine> lines, Allocation markupAllocation) {
        return new PurchaseOrder(1L, "PO-2026-001", null, 1L, LocalDate.of(2026, 1, 1),
                PurchaseOrderStatus.ONDERWEG, ContainerType.FORTY_HQ,
                bd("0.14"), bd("0.90"), bd("0.89"), bd("0"), bd("0"), Currency.USD,
                bd("0"), bd("0"), bd("0"), Allocation.VALUE, Allocation.VALUE, Allocation.VALUE,
                markupAllocation, "Ningbo", "Rotterdam", null, lines);
    }

    private PurchaseOrderLine line(long id, int received, int ordered, int damaged, boolean ddp) {
        return new PurchaseOrderLine(id, id, received, bd("10"), Currency.EUR, null, ordered,
                ddp ? PriceBasis.DDP : PriceBasis.EXW, damaged);
    }

    private LandedCost.Line cost(long product, int quantity, String goods, String logistics, String separate, String markup) {
        BigDecimal total = bd(goods).add(bd(logistics)).add(bd(separate)).add(bd(markup));
        return new LandedCost.Line(product, "Product " + product, quantity, 0, bd("0"), bd(goods), bd(goods),
                bd("0"), bd(logistics), bd(goods), bd("0"), "HS", bd("0"), bd("0"), bd(markup), total,
                bd("0"), bd("0"), bd("0"), bd("0"), bd(separate));
    }

    private PurchasePayment payment(PurchasePayment.Payee payee, String amount, boolean settles) {
        return new PurchasePayment(1L, 1L, LocalDate.of(2026, 1, 2), bd(amount), Currency.EUR,
                bd(amount), "Payment", null, null, payee, settles);
    }

    private PurchaseReconciliation.Stream stream(PurchaseReconciliation result, PurchasePayment.Payee payee) {
        return result.streams().stream().filter(stream -> stream.payee() == payee).findFirst().orElseThrow();
    }

    private void assertConserved(PurchaseReconciliation result) {
        assertEquals(result.totals().plannedExternalEur(), sum(result, PurchaseReconciliation.Line::plannedExternalEur));
        assertEquals(result.totals().paidEur(), sum(result, PurchaseReconciliation.Line::paidEur));
        assertEquals(result.totals().remainingEur(), sum(result, PurchaseReconciliation.Line::remainingEur));
        assertEquals(result.totals().forecastExternalEur(), sum(result, PurchaseReconciliation.Line::forecastExternalEur));
        assertEquals(result.totals().varianceEur(), sum(result, PurchaseReconciliation.Line::varianceEur));
        assertEquals(result.totals().internalMarkupEur(), sum(result, PurchaseReconciliation.Line::internalMarkupEur));
        assertEquals(result.totals().forecastPricingEur(), sum(result, PurchaseReconciliation.Line::forecastPricingEur));
    }

    private BigDecimal sum(PurchaseReconciliation result, Function<PurchaseReconciliation.Line, BigDecimal> field) {
        return result.lines().stream().map(field).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    private String amount(Random random) { return BigDecimal.valueOf(random.nextInt(100001), 2).toPlainString(); }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private void eq(String expected, BigDecimal actual) { assertEquals(new BigDecimal(expected), actual); }
    private record Fixture(PurchaseOrder order, LandedCost costing, PurchaseOrderService.Payable payable) {}
}
