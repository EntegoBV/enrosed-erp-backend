package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.HsCodeService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.Allocation;
import be.enrosed.sourcing.domain.ContainerType;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.LotCost;
import be.enrosed.sourcing.domain.LotCost.Accrual;
import be.enrosed.sourcing.domain.LotCost.BilledBasis;
import be.enrosed.sourcing.domain.LotCost.CreditTreatment;
import be.enrosed.sourcing.domain.LotCost.CreditUse;
import be.enrosed.sourcing.domain.LotCost.LotStatus;
import be.enrosed.sourcing.domain.LotCost.PaymentUse;
import be.enrosed.sourcing.domain.LotCost.QuantityBasis;
import be.enrosed.sourcing.domain.LotCost.State;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PriceBasis;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderLine;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import be.enrosed.sourcing.domain.PurchaseReconciliation;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit;
import be.enrosed.sourcing.domain.PurchaseSupplierCredit.Reason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static be.enrosed.sourcing.domain.PurchasePayment.Payee.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The lot cost goes into annual accounts. The two worked examples of the
 * inventory contract are asserted to the last decimal; when this test
 * deviates, the calculation is broken - not the test.
 */
class LotCostCalculatorTest {
    private static final long A = 1L, B = 2L, C = 3L, D = 4L;
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);
    private static final LocalDate DEC_5 = LocalDate.of(2026, 12, 5);

    /* ---- worked example 1: short delivery, damage, credits, a late foreign payment ---- */

    @Test
    void theFirstWorkedExampleGivesItsUnitValuesToTheLastDecimal() {
        Scenario example = exampleOne("1116.00");
        var container = example.run();

        var supplier = container.stream(SUPPLIER);
        assertEquals("PAID", supplier.status());
        eq("3680.00", supplier.plannedEur());
        eq("3692.00", supplier.paidEur(), "the deposit at its bank euro, the balance at the container rate");
        eq("0.00", supplier.openEur());
        eq("3692.00", supplier.includedEur());
        eq("0.00", supplier.estimatedEur());
        eq("0.00", supplier.overpaidEur());
        assertEquals(State.WERKELIJK, supplier.state());
        var logistics = container.stream(LOGISTICS);
        eq("1450.00", logistics.plannedEur());
        eq("1000.00", logistics.paidEur());
        eq("450.00", logistics.openEur());
        eq("1450.00", logistics.includedEur());
        eq("450.00", logistics.estimatedEur());
        assertEquals(State.GESCHAT, logistics.state());
        var separate = container.stream(SEPARATE);
        eq("180.00", separate.includedEur());
        assertEquals(State.WERKELIJK, separate.state());

        eq("28.00", container.exchangeDifferenceEur());
        eq("35.00", container.otherExcludedEur());
        eq("2000.00", container.enrosedCostExcludedEur());
        eq("92.00", container.priceCreditEur());
        eq("92.00", container.lossCreditEur());
        eq("3692.00", container.supplierGoodsEur());
        eq("0.00", container.supplierTransportEur());
        eq("5230.00", container.acquisitionEur());
        eq("450.00", container.estimatedEur());
        assertFalse(container.cif());
        assertFalse(container.separateInPiecePrice());
        assertTrue(container.hasShortage());
        assertFalse(container.supplierSettledLower());
        assertFalse(container.paymentWithoutEuro());
        assertEquals(List.of(), container.notes(), "inspection kept apart goes by received value: no fallback");

        var a = container.lot(A);
        assertEquals(1000, a.orderedQuantity());
        assertEquals(950, a.receivedQuantity());
        assertEquals(20, a.damagedQuantity());
        assertEquals(1000, a.billedQuantity());
        assertEquals(1000, a.goodsDivisor());
        assertEquals(950, a.costDivisor());
        assertEquals(930, a.capacity());
        assertEquals(LotStatus.TEKORT, a.status());
        eq("1.8400", a.unitPriceEur());
        eq("1840.00", a.goodsKey());
        assertNull(a.transportKey(), "not CIF");
        eq("620.00", a.logisticsKey());
        eq("1748.00", a.separateKey());
        eq("1846.00", a.goodsEur());
        eq("46.00", a.priceCreditEur());
        eq("0.00", a.transportEur());
        eq("620.00", a.logisticsEur());
        eq("87.69", a.separateEur());
        eq("2507.69", a.lotCostEur());
        eq("192.41", a.estimatedEur());
        eq("1.8000", a.unitGoodsEur());
        eq("0.0000", a.unitTransportEur());
        eq("0.6526", a.unitLogisticsEur());
        eq("0.0923", a.unitSeparateEur());
        eq("2.5449", a.unitValueEur());
        eq("0.2025", a.unitEstimatedEur());
        eq("90.00", a.missingCostEur(), "50 missing x 1,8000");
        eq("50.90", a.damagedCostEur(), "20 damaged x 2,5449");
        eq("0.00", a.calcOriginEur());
        eq("300.00", a.calcFreightEur());
        eq("120.00", a.calcDutyEur());
        eq("200.00", a.calcDestinationEur());
        eq("6.5000", a.calcDutyRatePct());

        var b = container.lot(B);
        assertEquals(500, b.capacity());
        assertEquals(LotStatus.OK, b.status());
        eq("1840.00", b.goodsKey());
        eq("830.00", b.logisticsKey());
        eq("1840.00", b.separateKey());
        eq("1846.00", b.goodsEur());
        eq("46.00", b.priceCreditEur());
        eq("830.00", b.logisticsEur());
        eq("92.31", b.separateEur());
        eq("257.59", b.estimatedEur());
        eq("3.6000", b.unitGoodsEur());
        eq("1.6600", b.unitLogisticsEur());
        eq("0.1846", b.unitSeparateEur());
        eq("5.4446", b.unitValueEur());
        eq("0.5152", b.unitEstimatedEur());
        eq("0.00", b.missingCostEur());
        eq("0.00", b.damagedCostEur());

        eq("140.90", container.missingAndDamagedCostEur());
        var price = credit(container, 1L);
        assertEquals(CreditTreatment.VERLAAGT, price.treatment());
        eq("92.00", price.countedEur(), "at the order rate, not the bank euro of the credit");
        assertFalse(price.decided());
        assertFalse(price.decisionRequired());
        var shortage = credit(container, 2L);
        assertEquals(CreditTreatment.BUITEN, shortage.treatment());
        assertFalse(shortage.decisionRequired(), "92,00 stays below the 140,90 the lost pieces cost");
        assertNull(shortage.requiredBy());

        var balance = payment(container, 2L);
        eq("2604.00", balance.storedEur());
        eq("2576.00", balance.countedEur());
        assertEquals(PaymentUse.RULE_GOODS_RATE, balance.rule());
        assertTrue(balance.inValue());
        assertEquals(PaymentUse.RULE_STORED, payment(container, 1L).rule());
        assertEquals(PaymentUse.RULE_EURO, payment(container, 3L).rule());
        var bank = payment(container, 5L);
        assertEquals(PaymentUse.RULE_OTHER, bank.rule());
        assertFalse(bank.inValue());
    }

    @Test
    void aDepositBookedAtAWorseRateKeepsItsOwnEuroAndLeavesNothingOpen() {
        var container = exampleOne("1080.00").run();
        var supplier = container.stream(SUPPLIER);
        eq("3656.00", supplier.includedEur());
        eq("0.00", supplier.openEur());
        eq("0.00", supplier.estimatedEur());
        assertEquals(State.WERKELIJK, supplier.state());
        eq("1828.00", container.lot(A).goodsEur());
        eq("1828.00", container.lot(B).goodsEur());
        eq("1.7820", container.lot(A).unitGoodsEur());
        eq("3.5640", container.lot(B).unitGoodsEur());
        eq("2.5269", container.lot(A).unitValueEur());
        eq("5.4086", container.lot(B).unitValueEur());
    }

    @Test
    void aStreamPaidInFullInItsOwnCurrencyHasNothingOpenWhateverTheBankCharged() {
        for (String bankEuro : List.of("900.00", "1104.00", "1116.00", "1400.00")) {
            var supplier = exampleOne(bankEuro).run().stream(SUPPLIER);
            eq("0.00", supplier.openEur(), bankEuro);
            eq("0.00", supplier.overpaidEur(), bankEuro);
            eq("0.00", supplier.estimatedEur(), bankEuro);
            assertEquals(new BigDecimal(bankEuro).add(new BigDecimal("2576.00")), supplier.includedEur(), bankEuro);
        }
    }

    @Test
    void aDepositAboveItsInstalmentLeavesNoPhantomOpenAmount() {
        Scenario plan = exampleOne("1116.00");
        plan.terms = PaymentTerms.DEPOSIT_30_70;
        plan.payments.clear();
        plan.pay(1L, SUPPLIER, SEP_1, "1500", Currency.USD, "1395.00", false, PaymentTerms.Moment.ORDERED);
        plan.pay(2L, SUPPLIER, DEC_5, "2500", Currency.USD, "2330.00", false, PaymentTerms.Moment.SHIPPED);
        plan.pay(3L, LOGISTICS, NOV_20, "1450", Currency.EUR, "1450.00");
        plan.pay(4L, SEPARATE, NOV_20, "180", Currency.EUR, "180.00");
        var container = plan.run();

        var reconciled = plan.reconciled(SUPPLIER);
        eq("276.00", reconciled.remainingEur(), "the reconciliation sums the gaps per term");
        assertEquals(PurchaseReconciliation.Status.OVERPAID, reconciled.status());
        eq("3680.00", reconciled.paidEur());

        var supplier = container.stream(SUPPLIER);
        assertEquals("OVERPAID", supplier.status(), "shown as the Nacalculatie shows it");
        eq("0.00", supplier.openEur());
        eq("0.00", supplier.overpaidEur());
        eq("0.00", supplier.estimatedEur());
        assertEquals(State.WERKELIJK, supplier.state());
        eq("3695.00", supplier.includedEur(), "the counted payments and nothing more: 1.395,00 + 2.300,00");
        eq("0.00", container.estimatedEur());
    }

    /* ---- worked example 2: CIF, CNY, an entered accrual, over-delivery, damage reported later ---- */

    @Test
    void theSecondWorkedExampleGivesItsUnitValuesToTheLastDecimal() {
        Scenario example = exampleTwo();
        example.accruals.put(SUPPLIER, new Accrual(bd("5150.00"), true, bd("5220.00")));
        var container = example.run();

        var supplier = container.stream(SUPPLIER);
        eq("9000.00", supplier.plannedEur());
        eq("3810.00", supplier.paidEur());
        eq("5220.00", supplier.openEur(), "the open Afspraak before the accrual: 9.000,00 - 3.780,00");
        eq("8960.00", supplier.includedEur());
        eq("0.00", supplier.estimatedEur());
        assertEquals(State.BEVESTIGD, supplier.state());
        assertTrue(supplier.accrualApplied());
        assertFalse(supplier.accrualStale());
        assertTrue(container.cif());
        assertTrue(container.separateInPiecePrice());
        eq("7526.40", container.supplierGoodsEur());
        eq("1433.60", container.supplierTransportEur());
        eq("0.00", container.exchangeDifferenceEur(), "paid before the purchase: the bank euro is the cost");
        eq("0.00", container.estimatedEur());
        eq("10310.00", container.acquisitionEur());
        assertEquals(List.of(), container.notes());

        var c = container.lot(C);
        assertEquals(LotStatus.OK, c.status());
        assertEquals(600, c.capacity(), "the 6 pieces reported later left through the ledger, not through the lot");
        assertEquals(6, c.laterLostQuantity());
        eq("6.3000", c.unitPriceEur());
        eq("3780.00", c.goodsKey());
        eq("900.00", c.transportKey());
        eq("560.00", c.logisticsKey());
        eq("98.90", c.separateKey());
        eq("3763.20", c.goodsEur());
        eq("896.00", c.transportEur());
        eq("553.09", c.logisticsEur());
        eq("98.90", c.separateEur());
        eq("6.2720", c.unitGoodsEur());
        eq("1.4933", c.unitTransportEur());
        eq("0.9218", c.unitLogisticsEur());
        eq("0.1648", c.unitSeparateEur());
        eq("8.8519", c.unitValueEur());
        eq("0.0000", c.unitEstimatedEur());
        eq("0.00", c.missingCostEur());
        eq("53.11", c.damagedCostEur(), "6 x 8,8519, for information");

        var d = container.lot(D);
        assertEquals(LotStatus.MEER_ONTVANGEN, d.status());
        assertEquals(300, d.billedQuantity());
        assertEquals(310, d.goodsDivisor());
        assertEquals(310, d.costDivisor());
        assertEquals(300, d.capacity());
        eq("3780.00", d.goodsKey());
        eq("540.00", d.transportKey());
        eq("655.00", d.logisticsKey());
        eq("51.10", d.separateKey());
        eq("3763.20", d.goodsEur());
        eq("537.60", d.transportEur());
        eq("646.91", d.logisticsEur());
        eq("51.10", d.separateEur());
        eq("12.1394", d.unitGoodsEur());
        eq("1.7342", d.unitTransportEur());
        eq("2.0868", d.unitLogisticsEur());
        eq("0.1648", d.unitSeparateEur());
        eq("16.1252", d.unitValueEur());
        eq("0.00", d.missingCostEur(), "more arrived than was billed: nothing is missing");
        eq("161.25", d.damagedCostEur());
    }

    @Test
    void withoutTheAccrualTheOpenAfspraakIsCarriedAsAnEstimate() {
        var container = exampleTwo().run();
        var supplier = container.stream(SUPPLIER);
        eq("5220.00", supplier.openEur());
        eq("9030.00", supplier.includedEur());
        eq("5220.00", supplier.estimatedEur());
        assertEquals(State.GESCHAT, supplier.state());
        assertFalse(supplier.accrualApplied());
        eq("5220.00", container.estimatedEur());
        /* The estimate is split like the amount: 5.220,00 x 7.560 / 9.000 for the goods. */
        eq("4384.80", container.lot(C).estimatedEur().add(container.lot(D).estimatedEur())
                .subtract(estimatedTransport(container)));
    }

    @Test
    void anAccrualReplacesTheOpenAmountAndOnlyAnInvoiceClearsTheEstimate() {
        Scenario stated = exampleTwo();
        stated.accruals.put(SUPPLIER, new Accrual(bd("5150.00"), false, bd("5220.00")));
        var supplier = stated.run().stream(SUPPLIER);
        eq("8960.00", supplier.includedEur());
        eq("5150.00", supplier.estimatedEur(), "stated, but no invoice yet");
        assertEquals(State.GESCHAT, supplier.state());
        assertTrue(supplier.accrualApplied());

        Scenario stale = exampleTwo();
        stale.accruals.put(SUPPLIER, new Accrual(bd("5150.00"), true, bd("5000.00")));
        supplier = stale.run().stream(SUPPLIER);
        assertFalse(supplier.accrualApplied(), "confirmed against another open amount");
        assertTrue(supplier.accrualStale());
        eq("5220.00", supplier.openEur());
        eq("9030.00", supplier.includedEur());
        eq("5220.00", supplier.estimatedEur());
        assertEquals(State.GESCHAT, supplier.state());
    }

    /* ---- payments ---- */

    @Test
    void theEnrosedKostOfAnySizeAndAnyKeyChangesNothing() {
        var modest = exampleOne("1116.00").run();
        Scenario heavy = exampleOne("1116.00");
        heavy.enrosed = "99999.00";
        heavy.allocExtra = Allocation.MANUAL;
        var loaded = heavy.run();
        assertEquals(modest.lots(), loaded.lots());
        assertEquals(modest.streams(), loaded.streams());
        eq("5230.00", loaded.acquisitionEur());
        eq("99999.00", loaded.enrosedCostExcludedEur(), "copied for information only");
    }

    @Test
    void bijkomendeKostenAreExcludedAndReported() {
        Scenario with = exampleOne("1116.00");
        with.pay(6L, OTHER, DEC_5, "500", Currency.USD, "470.00");
        var container = with.run();
        eq("495.00", container.otherExcludedEur(), "35,00 plus the USD 500 at the container rate");
        eq("38.00", container.exchangeDifferenceEur(), "listed for every payment, also one outside the value");
        assertFalse(payment(container, 6L).inValue());
        assertEquals(PaymentUse.RULE_OTHER, payment(container, 6L).rule());
        assertEquals(exampleOne("1116.00").run().lots(), container.lots());
    }

    @Test
    void aForeignPaymentOnTheBorderDateKeepsItsBankEuroAndALaterOneCountsAtTheContainerRate() {
        Scenario onTheDay = exampleOne("1116.00");
        onTheDay.payments.set(1, payment(2L, SUPPLIER, NOV_20, "2800", Currency.USD, "2604.00", false, null));
        var container = onTheDay.run();
        eq("2604.00", payment(container, 2L).countedEur());
        assertEquals(PaymentUse.RULE_STORED, payment(container, 2L).rule());
        eq("0.00", container.exchangeDifferenceEur());
        eq("3720.00", container.stream(SUPPLIER).includedEur());

        Scenario dayAfter = exampleOne("1116.00");
        dayAfter.payments.set(1, payment(2L, SUPPLIER, NOV_20.plusDays(1), "2800", Currency.USD, "2604.00", false, null));
        container = dayAfter.run();
        eq("2576.00", payment(container, 2L).countedEur());
        eq("28.00", container.exchangeDifferenceEur());
        eq("3692.00", container.stream(SUPPLIER).includedEur());
    }

    @Test
    void oneBorderGivesTheSameBalanceInTheYearOnTheWaterAndInTheYearOfReceipt() {
        LocalDate ownership = LocalDate.of(2026, 12, 15);
        LocalDate received = LocalDate.of(2027, 1, 20);
        /* Closing 2026: included in transit, ownership passed on 15/12. */
        Scenario onTheWater = twoClosings(PurchaseOrderStatus.ONDERWEG, null);
        onTheWater.rateCutoff = ownership;
        onTheWater.quantityBasis = QuantityBasis.BESTELD;
        onTheWater.costCutoff = LocalDate.of(2026, 12, 31);
        var first = onTheWater.run();
        eq("2576.00", payment(first, 2L).countedEur());
        eq("3692.00", first.stream(SUPPLIER).includedEur(), "the supplier stream is taken in full");
        eq("28.00", first.exchangeDifferenceEur());

        /* Closing 2027: the same border, taken over from the previous closing. */
        Scenario arrived = twoClosings(PurchaseOrderStatus.ONTVANGEN, received);
        arrived.rateCutoff = ownership;
        var second = arrived.run();
        eq("2576.00", payment(second, 2L).countedEur());
        eq("28.00", second.exchangeDifferenceEur());
        assertEquals(first.lot(A).unitGoodsEur(), second.lot(A).unitGoodsEur());

        /* What a second border would have done, and why the contract refuses it. */
        arrived.rateCutoff = received;
        eq("2604.00", payment(arrived.run(), 2L).countedEur());
    }

    @Test
    void aDollarPaymentToTheForwarderUsesTheTransportRateWhenTheContainerHasOne() {
        Scenario freight = exampleOne("1116.00");
        freight.transportRate = bd("0.95");
        freight.logisticsPlanned = "1900.00";
        freight.payments.set(2, payment(3L, LOGISTICS, DEC_5, "2000", Currency.USD, "1930.00", false, null));
        var container = freight.run();
        eq("1900.00", payment(container, 3L).countedEur());
        assertEquals(PaymentUse.RULE_TRANSPORT_RATE, payment(container, 3L).rule());
        eq("0.00", container.stream(LOGISTICS).openEur());
        eq("1900.00", container.stream(LOGISTICS).includedEur());
        eq("2576.00", payment(container, 2L).countedEur(), "the supplier keeps the goods rate");

        freight.transportRate = null;
        freight.logisticsPlanned = "1840.00";
        container = freight.run();
        eq("1840.00", payment(container, 3L).countedEur());
        assertEquals(PaymentUse.RULE_GOODS_RATE, payment(container, 3L).rule());
        eq("0.00", container.stream(LOGISTICS).openEur());
    }

    @Test
    void aPaymentWithoutEuroValueCountsForNothingAndIsFlagged() {
        Scenario legacy = exampleOne("1116.00");
        legacy.payments.set(3, payment(4L, SEPARATE, NOV_20, "180", Currency.EUR, null, false, null));
        var container = legacy.run();
        assertTrue(container.paymentWithoutEuro());
        assertNull(payment(container, 4L).storedEur());
        eq("0.00", payment(container, 4L).countedEur());
        eq("0.00", container.stream(SEPARATE).paidEur());
    }

    @Test
    void anOverpaidStreamReportsTheAmountAndIsIncludedInFull() {
        Scenario more = exampleOne("1116.00");
        more.payments.set(2, payment(3L, LOGISTICS, NOV_20, "1500", Currency.EUR, "1500.00", false, null));
        var logistics = more.run().stream(LOGISTICS);
        eq("50.00", logistics.overpaidEur());
        eq("0.00", logistics.openEur());
        eq("1500.00", logistics.includedEur());
        eq("0.00", logistics.estimatedEur());
        assertEquals(State.WERKELIJK, logistics.state());
        assertEquals("OVERPAID", logistics.status());
    }

    @Test
    void anAccrualOnAStreamWithNothingOpenAddsItsAmount() {
        Scenario extra = exampleOne("1116.00");
        extra.payments.set(2, payment(3L, LOGISTICS, NOV_20, "1450", Currency.EUR, "1450.00", false, null));
        extra.accruals.put(LOGISTICS, new Accrual(bd("80.00"), true, bd("0.00")));
        var logistics = extra.run().stream(LOGISTICS);
        eq("0.00", logistics.openEur());
        assertTrue(logistics.accrualApplied());
        eq("1530.00", logistics.includedEur());
        eq("0.00", logistics.estimatedEur());
        assertEquals(State.BEVESTIGD, logistics.state());
    }

    @Test
    void onTheWaterOnlyWhatWasPaidByTheClosingDateCountsForTransportAndInspection() {
        Scenario transit = twoClosings(PurchaseOrderStatus.ONDERWEG, null);
        transit.rateCutoff = LocalDate.of(2026, 12, 15);
        transit.quantityBasis = QuantityBasis.BESTELD;
        transit.costCutoff = LocalDate.of(2026, 12, 31);
        transit.pay(3L, LOGISTICS, LocalDate.of(2026, 12, 31), "400", Currency.EUR, "400.00");
        transit.pay(4L, LOGISTICS, LocalDate.of(2027, 1, 2), "600", Currency.EUR, "600.00");
        transit.accruals.put(SEPARATE, new Accrual(bd("75.00"), false, bd("999.00")));
        var container = transit.run();

        var logistics = container.stream(LOGISTICS);
        eq("400.00", logistics.paidEur());
        eq("0.00", logistics.openEur(), "the Afspraak of 1.450,00 is not accrued for goods on the water");
        eq("400.00", logistics.includedEur());
        assertEquals(State.WERKELIJK, logistics.state());
        assertFalse(payment(container, 4L).inValue());
        assertEquals(PaymentUse.RULE_AFTER_CLOSING, payment(container, 4L).rule());
        var separate = container.stream(SEPARATE);
        assertTrue(separate.accrualApplied(), "added without a staleness check");
        assertFalse(separate.accrualStale());
        eq("75.00", separate.includedEur());
        eq("75.00", separate.estimatedEur());

        var a = container.lot(A);
        assertEquals(1000, a.receivedQuantity(), "ordered quantities stand in for the receipt");
        assertEquals(0, a.damagedQuantity());
        assertEquals(1000, a.capacity());
        assertEquals(LotStatus.OK, a.status());
        assertEquals(QuantityBasis.BESTELD, container.quantityBasis());
    }

    /* ---- credits ---- */

    @Test
    void aPriceCreditLowersTheUnitValueAndACreditForLossDoesNot() {
        Scenario none = exampleOne("1116.00");
        none.credits.clear();
        eq("1.8460", none.run().lot(A).unitGoodsEur());

        for (Reason loss : List.of(Reason.SHORTAGE, Reason.DAMAGE)) {
            Scenario outside = exampleOne("1116.00");
            outside.credits.clear();
            outside.credit(7L, loss, "100", Currency.USD);
            var container = outside.run();
            eq("1.8460", container.lot(A).unitGoodsEur(), loss.name());
            eq("0.00", container.priceCreditEur());
            eq("92.00", container.lossCreditEur());
            eq("92.00", container.defaultLossCreditEur());
            assertEquals(CreditTreatment.BUITEN, credit(container, 7L).treatment());
        }

        Scenario lower = exampleOne("1116.00");
        lower.credits.clear();
        lower.credit(7L, Reason.PRICE, "100", Currency.USD);
        var container = lower.run();
        eq("1.8000", container.lot(A).unitGoodsEur());
        eq("92.00", container.priceCreditEur());
        eq("0.00", container.lossCreditEur());
    }

    @Test
    void aCreditWithoutAKindIsNotSubtractedUntilATreatmentIsPassed() {
        for (Reason reason : new Reason[] {Reason.OTHER, null}) {
            Scenario open = exampleOne("1116.00");
            open.credits.clear();
            open.credit(7L, reason, "100", Currency.USD);
            var container = open.run();
            var use = credit(container, 7L);
            assertNull(use.treatment());
            assertEquals("NOG_TE_BESLISSEN", use.treatmentCode());
            assertTrue(use.decisionRequired());
            assertEquals("TEGOED_ZONDER_SOORT", use.requiredBy());
            eq("0.00", container.priceCreditEur());
            eq("0.00", container.lossCreditEur());
            eq("1.8460", container.lot(A).unitGoodsEur());
        }
    }

    @Test
    void eachOfTheThreeTreatmentsCanBePassedForOneCredit() {
        Map<CreditTreatment, String[]> expected = new EnumMap<>(CreditTreatment.class);
        expected.put(CreditTreatment.VERLAAGT, new String[] {"92.00", "0.00", "1.8000"});
        expected.put(CreditTreatment.BUITEN, new String[] {"0.00", "92.00", "1.8460"});
        expected.put(CreditTreatment.IN_BETALING, new String[] {"0.00", "0.00", "1.8460"});
        expected.forEach((treatment, figures) -> {
            Scenario decided = exampleOne("1116.00");
            decided.credits.clear();
            decided.credit(7L, Reason.OTHER, "100", Currency.USD);
            decided.treatments.put(7L, treatment);
            var container = decided.run();
            var use = credit(container, 7L);
            assertEquals(treatment, use.treatment());
            assertEquals(treatment.name(), use.treatmentCode());
            assertTrue(use.decided());
            assertFalse(use.decisionRequired());
            eq(figures[0], container.priceCreditEur(), treatment.name());
            eq(figures[1], container.lossCreditEur(), treatment.name());
            eq("0.00", container.defaultLossCreditEur(), "a decided credit is no default");
            eq(figures[2], container.lot(A).unitGoodsEur(), treatment.name());
        });
    }

    @Test
    void settledLowerWithAPriceCreditForcesADecision() {
        /* Lot B alone: Afspraak 1.840,00, the supplier grants 92,00 and is paid 1.748,00, "vereffend". */
        var silent = lotB(true).run();
        assertTrue(silent.supplierSettledLower());
        assertEquals("SETTLED_LOWER", silent.stream(SUPPLIER).status());
        var use = credit(silent, 7L);
        assertEquals(CreditTreatment.VERLAAGT, use.treatment(), "the default stands in the concept figures");
        assertTrue(use.decisionRequired());
        assertEquals("TEGOED_EN_LAGER_AFGEREKEND", use.requiredBy());
        eq("3.3120", silent.lot(B).unitValueEur());

        Scenario inPayment = lotB(true);
        inPayment.treatments.put(7L, CreditTreatment.IN_BETALING);
        var container = inPayment.run();
        assertFalse(credit(container, 7L).decisionRequired());
        eq("1748.00", container.lot(B).goodsEur());
        eq("0.00", container.priceCreditEur());
        eq("3.4960", container.lot(B).unitValueEur());

        Scenario onTop = lotB(true);
        onTop.treatments.put(7L, CreditTreatment.VERLAAGT);
        container = onTop.run();
        assertFalse(credit(container, 7L).decisionRequired());
        eq("1656.00", container.lot(B).lotCostEur());
        eq("3.3120", container.lot(B).unitValueEur());
    }

    @Test
    void settledLowerWithoutACreditTakesWhatWasPaidUntilAnAccrualPutsTheDifferenceBack() {
        var container = lotB(false).run();
        var supplier = container.stream(SUPPLIER);
        assertEquals("SETTLED_LOWER", supplier.status());
        eq("1840.00", supplier.plannedEur());
        eq("0.00", supplier.openEur());
        eq("1748.00", supplier.includedEur());
        assertFalse(supplier.accrualApplied());
        eq("3.4960", container.lot(B).unitValueEur());

        Scenario discount = lotB(false);
        discount.accruals.put(SUPPLIER, new Accrual(bd("92.00"), true, bd("0.00")));
        container = discount.run();
        supplier = container.stream(SUPPLIER);
        assertTrue(supplier.accrualApplied());
        eq("1840.00", supplier.includedEur());
        eq("3.6800", container.lot(B).unitValueEur());
    }

    @Test
    void moreLossCreditThanTheLostPiecesCostIsReported() {
        Scenario above = exampleOne("1116.00");
        above.credit(3L, Reason.DAMAGE, "49", Currency.EUR);
        var container = above.run();
        eq("141.00", container.defaultLossCreditEur());
        eq("140.90", container.missingAndDamagedCostEur());
        assertEquals("TEGOED_MEER_DAN_VERLIES", credit(container, 2L).requiredBy());
        assertEquals("TEGOED_MEER_DAN_VERLIES", credit(container, 3L).requiredBy());
        assertFalse(credit(container, 1L).decisionRequired(), "the price credit is not in question");

        Scenario atTheCost = exampleOne("1116.00");
        atTheCost.credit(3L, Reason.DAMAGE, "48.90", Currency.EUR);
        container = atTheCost.run();
        eq("140.90", container.defaultLossCreditEur());
        assertFalse(credit(container, 2L).decisionRequired());
        assertFalse(credit(container, 3L).decisionRequired());

        /* Deciding one of them leaves the rest below the cost again. */
        above.treatments.put(3L, CreditTreatment.BUITEN);
        container = above.run();
        eq("92.00", container.defaultLossCreditEur());
        eq("141.00", container.lossCreditEur());
        assertFalse(credit(container, 2L).decisionRequired());
    }

    /* ---- quantities ---- */

    @Test
    void aShortContainerSettledLowerDividesByWhatTheSupplierBilled() {
        var ordered = shortAndSettled(BilledBasis.BESTELD, 0).run();
        assertTrue(ordered.supplierSettledLower());
        assertTrue(ordered.hasShortage());
        assertEquals(1000, ordered.lot(A).billedQuantity());
        assertEquals(1000, ordered.lot(A).goodsDivisor());
        eq("1840.00", ordered.lot(A).goodsKey());
        eq("1.7480", ordered.lot(A).unitGoodsEur(), "the lower amount is a price reduction on 1.000 pieces");
        eq("87.40", ordered.lot(A).missingCostEur());

        var delivered = shortAndSettled(BilledBasis.GELEVERD, 0).run();
        assertEquals(950, delivered.lot(A).billedQuantity());
        assertEquals(950, delivered.lot(A).goodsDivisor());
        eq("1748.00", delivered.lot(A).goodsKey());
        eq("1.8400", delivered.lot(A).unitGoodsEur(), "the supplier charged the 950 that arrived");
        eq("0.00", delivered.lot(A).missingCostEur());
        assertEquals(BilledBasis.GELEVERD, delivered.billedBasis());
    }

    @Test
    void damagedPiecesLowerTheCapacityNotTheUnitValue() {
        var whole = shortAndSettled(BilledBasis.BESTELD, 0).run().lot(A);
        var broken = shortAndSettled(BilledBasis.BESTELD, 20).run().lot(A);
        assertEquals(950, whole.capacity());
        assertEquals(930, broken.capacity());
        assertEquals(whole.unitValueEur(), broken.unitValueEur());
        assertEquals(whole.lotCostEur(), broken.lotCostEur());
        eq("0.00", whole.damagedCostEur());
        eq("34.96", broken.damagedCostEur(), "20 x 1,7480");
    }

    @Test
    void laterLossChangesNeitherCapacityNorUnitValueAndIsReported() {
        Scenario later = shortAndSettled(BilledBasis.BESTELD, 20);
        later.loss.add(loss(A, StockMovement.Kind.DAMAGED, -4, "2026-12-01T10:00:00Z"));
        later.loss.add(loss(A, StockMovement.Kind.SHORTAGE, -2, "2026-12-02T10:00:00Z"));
        /* Booked at the cut-off or later, or not a loss at all: not this closing's information. */
        later.loss.add(loss(A, StockMovement.Kind.DAMAGED, -9, "2026-12-31T23:00:00Z"));
        later.loss.add(loss(A, StockMovement.Kind.DEMO, -3, "2026-12-03T10:00:00Z"));
        var reported = later.run().lot(A);
        var plain = shortAndSettled(BilledBasis.BESTELD, 20).run().lot(A);
        assertEquals(6, reported.laterLostQuantity());
        assertEquals(0, plain.laterLostQuantity());
        assertEquals(plain.capacity(), reported.capacity());
        assertEquals(plain.unitValueEur(), reported.unitValueEur());
        eq("45.45", reported.damagedCostEur(), "(20 + 6) x 1,7480");
    }

    @Test
    void aProductWithoutAnyPriceIsNamed() {
        Scenario free = new Scenario();
        free.line(A, 100, 100, 0, "0", Currency.USD);
        free.line(B, 100, 100, 0, "4.00", Currency.USD);
        free.supplierPlanned = "368.00";
        free.pay(1L, SUPPLIER, SEP_1, "368", Currency.EUR, "368.00");
        var container = free.run();
        assertEquals(LotStatus.GEEN_PRIJS, container.lot(A).status());
        assertEquals(LotStatus.OK, container.lot(B).status());
        eq("0.0000", container.lot(A).unitValueEur());
        eq("3.6800", container.lot(B).unitValueEur());
    }

    @Test
    void aLotThatNeverArrivedKeepsItsAmountsAndIsNeverALayer() {
        Scenario absent = new Scenario();
        absent.line(A, 100, 0, 0, "2.00", Currency.USD);
        absent.line(B, 100, 100, 0, "4.00", Currency.USD);
        absent.line(C, 0, 0, 0, "3.00", Currency.USD);
        absent.brec(B, "0", "150", "50", "100", "0");
        absent.supplierPlanned = "552.00";
        absent.logisticsPlanned = "300.00";
        absent.pay(1L, SUPPLIER, SEP_1, "552", Currency.EUR, "552.00");
        absent.pay(2L, LOGISTICS, SEP_1, "300", Currency.EUR, "300.00");
        var container = absent.run();

        var never = container.lot(A);
        assertEquals(LotStatus.GEEN_ONTVANGST, never.status());
        assertEquals(0, never.capacity());
        assertEquals(0, never.costDivisor());
        eq("184.00", never.goodsEur(), "its goods stay its own: nothing is loaded onto the pieces that came");
        eq("0.0000", never.unitTransportEur());
        eq("0.0000", never.unitLogisticsEur());
        eq("0.0000", never.unitSeparateEur());
        eq("1.8400", never.unitGoodsEur());
        eq("184.00", never.missingCostEur(), "100 missing x 1,8400");

        var empty = container.lot(C);
        assertEquals(LotStatus.GEEN_ONTVANGST, empty.status());
        assertEquals(0, empty.goodsDivisor());
        assertEquals(0, empty.capacity());
        eq("0.0000", empty.unitGoodsEur());
        eq("0.0000", empty.unitValueEur());
        eq("0.00", empty.lotCostEur());

        eq("3.6800", container.lot(B).unitGoodsEur());
        eq("3.0000", container.lot(B).unitLogisticsEur());
        eq("852.00", container.acquisitionEur());
    }

    @Test
    void aLineWhoseProductNoLongerExistsIsSkippedAndNamed() {
        Scenario gone = exampleOne("1116.00");
        gone.products.remove(B);
        var container = gone.run();
        assertEquals(List.of(A), container.lots().stream().map(LotCost.Lot::productId).toList());
        assertEquals(List.of("Product 2 bestaat niet meer: de regel is niet opgenomen."), container.notes());
        eq("3692.00", container.lot(A).goodsEur());
    }

    @Test
    void severalLinesOfOneProductAreOneLot() {
        Scenario twice = new Scenario();
        twice.line(A, 600, 600, 5, "2.00", Currency.USD);
        twice.line(A, 400, 350, 15, "2.50", Currency.USD);
        twice.supplierPlanned = "2024.00";
        twice.pay(1L, SUPPLIER, SEP_1, "2024", Currency.EUR, "2024.00");
        var lot = twice.run().lot(A);
        assertEquals(1000, lot.orderedQuantity());
        assertEquals(950, lot.receivedQuantity());
        assertEquals(20, lot.damagedQuantity());
        assertEquals(930, lot.capacity());
        eq("2024.00", lot.goodsKey(), "600 x 1,84 + 400 x 2,30");
        eq("2.0240", lot.unitPriceEur());
        eq("2.0240", lot.unitGoodsEur());
    }

    /* ---- allocation ---- */

    @Test
    void cifAndNotCifWithTheSameMoneyGiveTheSameLotCost() {
        Scenario forwarder = sameMoney(false);
        Scenario viaSupplier = sameMoney(true);
        var plain = forwarder.run();
        var cif = viaSupplier.run();
        assertFalse(plain.cif());
        assertTrue(cif.cif());
        eq("1800.00", cif.supplierGoodsEur());
        eq("500.00", cif.supplierTransportEur());
        for (long product : List.of(A, B)) {
            assertEquals(plain.lot(product).lotCostEur(), cif.lot(product).lotCostEur());
            assertEquals(plain.lot(product).goodsEur(), cif.lot(product).goodsEur());
            assertEquals(plain.lot(product).logisticsEur(),
                    cif.lot(product).logisticsEur().add(cif.lot(product).transportEur()));
            BigDecimal difference = plain.lot(product).unitValueEur().subtract(cif.lot(product).unitValueEur()).abs();
            assertTrue(difference.compareTo(new BigDecimal("0.0001")) <= 0, "unit values differ by " + difference);
            assertNull(plain.lot(product).transportKey());
            assertNotNull(cif.lot(product).transportKey());
        }
        eq("3.0928", plain.lot(A).unitLogisticsEur());
        eq("2.0619", cif.lot(A).unitTransportEur());
        eq("1.0309", cif.lot(A).unitLogisticsEur());
        eq("750.00", cif.lot(A).calcOriginEur().add(cif.lot(A).calcFreightEur()).add(cif.lot(A).calcDutyEur())
                .add(cif.lot(A).calcDestinationEur()).add(cif.lot(B).calcOriginEur()).add(cif.lot(B).calcFreightEur())
                .add(cif.lot(B).calcDutyEur()).add(cif.lot(B).calcDestinationEur()),
                "the informational split covers transport and Douane & transport together");
        assertEquals(plain.lot(A).calcFreightEur(), cif.lot(A).calcFreightEur());
    }

    @Test
    void aDdpLineTakesNoContainerCost() {
        Scenario mixed = new Scenario();
        mixed.allocSeparate = Allocation.VALUE;
        mixed.line(A, 100, 100, 0, "10.00", Currency.USD);
        mixed.ddpLine(B, 100, 100, "20.00", Currency.USD);
        mixed.brec(A, "40", "160", "60", "40", "50");
        mixed.supplierPlanned = "2760.00";
        mixed.logisticsPlanned = "300.00";
        mixed.separatePlanned = "50.00";
        mixed.pay(1L, SUPPLIER, SEP_1, "2760", Currency.EUR, "2760.00");
        mixed.pay(2L, LOGISTICS, SEP_1, "300", Currency.EUR, "300.00");
        mixed.pay(3L, SEPARATE, SEP_1, "50", Currency.EUR, "50.00");
        var container = mixed.run();
        var delivered = container.lot(B);
        eq("0.00", delivered.logisticsEur());
        eq("0.00", delivered.separateEur());
        eq("0.00", delivered.logisticsKey());
        eq("18.4000", delivered.unitValueEur());
        eq("18.4000", delivered.unitGoodsEur());
        eq("300.00", container.lot(A).logisticsEur());
        eq("50.00", container.lot(A).separateEur());

        /* Without a calculation the fallback still leaves the DDP product out. */
        mixed.brecLines.clear();
        container = mixed.run();
        eq("0.00", container.lot(B).logisticsEur());
        eq("0.00", container.lot(B).logisticsKey());
        eq("920.00", container.lot(A).logisticsKey());
        assertTrue(container.notes().contains(
                "Douane & transport verdeeld volgens ontvangen waarde: de berekening gaf geen sleutel."), container.notes().toString());
    }

    @Test
    void inspectionKeptApartIsStillInTheValue() {
        var container = exampleOne("1116.00").run();
        assertEquals("SEPARATE", container.allocSeparate());
        assertFalse(container.separateInPiecePrice());
        eq("180.00", container.lot(A).separateEur().add(container.lot(B).separateEur()));
        assertTrue(container.lot(A).unitSeparateEur().signum() > 0);
    }

    @Test
    void theFourAllocationSettingsOfTheOrderAreReported() {
        Scenario settings = exampleOne("1116.00");
        settings.allocOrigin = Allocation.PIECES;
        settings.allocFreight = Allocation.CBM;
        settings.allocDestination = Allocation.VALUE;
        settings.allocSeparate = Allocation.PIECES;
        settings.groupVariants = true;
        var container = settings.run();
        assertEquals("PIECES", container.allocOrigin());
        assertEquals("CBM", container.allocFreight());
        assertEquals("VALUE", container.allocDestination());
        assertEquals("PIECES", container.allocSeparate());
        assertTrue(container.groupVariants());
        assertTrue(container.separateInPiecePrice());
        /* Spread into the piece price but the calculation holds no share: received value, and the container says so. */
        eq("1748.00", container.lot(A).separateKey());
        assertEquals(List.of("Inspectie & andere kosten verdeeld volgens ontvangen waarde: de berekening gaf geen sleutel."),
                container.notes());
    }

    @Test
    void everyFallbackKeyIsNamedInTheNotes() {
        Scenario bare = new Scenario();
        bare.cif = true;
        bare.line(A, 100, 100, 0, "0", Currency.USD);
        bare.line(B, 300, 300, 0, "0", Currency.USD);
        bare.supplierPlanned = "1000.00";
        bare.supplierFreight = "200.00";
        bare.logisticsPlanned = "400.00";
        bare.separatePlanned = "40.00";
        bare.pay(1L, SUPPLIER, SEP_1, "1000", Currency.EUR, "1000.00");
        bare.pay(2L, LOGISTICS, SEP_1, "400", Currency.EUR, "400.00");
        bare.pay(3L, SEPARATE, SEP_1, "40", Currency.EUR, "40.00");
        var container = bare.run();
        assertEquals(List.of(
                "Goederen verdeeld volgens aangerekende stuks: de berekening gaf geen sleutel.",
                "Transport via leverancier verdeeld volgens ontvangen stuks: de berekening gaf geen sleutel.",
                "Douane & transport verdeeld volgens ontvangen stuks: de berekening gaf geen sleutel.",
                "Inspectie & andere kosten verdeeld volgens ontvangen stuks: de berekening gaf geen sleutel."),
                container.notes());
        eq("100.00", container.lot(A).goodsKey());
        eq("300.00", container.lot(B).goodsKey());
        eq("100.00", container.lot(A).transportKey());
        eq("200.00", container.lot(A).goodsEur());
        eq("600.00", container.lot(B).goodsEur());
        eq("50.00", container.lot(A).transportEur());
        eq("100.00", container.lot(A).logisticsEur());
        eq("10.00", container.lot(A).separateEur());

        /* Nothing to weigh at all: equal parts. */
        Scenario nothing = new Scenario();
        nothing.line(A, 0, 0, 0, "0", Currency.USD);
        nothing.line(B, 0, 0, 0, "0", Currency.USD);
        nothing.supplierPlanned = "10.01";
        nothing.pay(1L, SUPPLIER, SEP_1, "10.01", Currency.EUR, "10.01");
        container = nothing.run();
        assertEquals(List.of("Goederen verdeeld volgens gelijke delen: de berekening gaf geen sleutel."), container.notes());
        eq("1.00", container.lot(A).goodsKey());
        eq("5.01", container.lot(A).goodsEur());
        eq("5.00", container.lot(B).goodsEur());
    }

    @Test
    void variantsOfOneFamilyGetTheSameContainerCostPerPieceAndKeepTheirOwnGoods() {
        HsCodeService hsCodes = mock(HsCodeService.class);
        when(hsCodes.dutyRateFor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("10"));
        LandedCostCalculator landed = new LandedCostCalculator(new CurrencyConverter(), hsCodes);

        Scenario family = new Scenario();
        family.goodsRate = family.transportRate = bd("0.90");
        family.freightUsd = "2000";
        family.destinationCosts = "600";
        family.line(A, 100, 100, 0, "5.00", Currency.USD);
        family.line(B, 300, 300, 0, "8.00", Currency.USD);
        family.line(C, 200, 200, 0, "3.00", Currency.USD);
        family.products.put(A, product(A, 7L));
        family.products.put(B, product(B, 7L));
        family.supplierPlanned = "3150.00";
        family.pay(1L, SUPPLIER, SEP_1, "3150", Currency.EUR, "3150.00");
        family.pay(2L, LOGISTICS, SEP_1, "2000", Currency.EUR, "2000.00");

        family.groupVariants = false;
        family.calculation = landed.calculate(family.order(), family.products);
        family.logisticsPlanned = family.calculation.totals().freightEur().add(family.calculation.totals().dutyEur())
                .add(family.calculation.totals().destinationEur()).toPlainString();
        var apart = family.run();
        assertTrue(perPiece(apart.lot(A).logisticsKey(), 100).subtract(perPiece(apart.lot(B).logisticsKey(), 300))
                .abs().compareTo(new BigDecimal("0.01")) > 0, "the dearer variant pays more duty per piece");

        family.groupVariants = true;
        family.calculation = landed.calculate(family.order(), family.products);
        var levelled = family.run();
        assertTrue(levelled.groupVariants());
        assertTrue(perPiece(levelled.lot(A).logisticsKey(), 100).subtract(perPiece(levelled.lot(B).logisticsKey(), 300))
                .abs().compareTo(new BigDecimal("0.001")) <= 0, "levelled per family by pieces");
        assertTrue(levelled.lot(A).unitLogisticsEur().subtract(levelled.lot(B).unitLogisticsEur())
                .abs().compareTo(new BigDecimal("0.001")) <= 0);
        eq("4.5000", levelled.lot(A).unitGoodsEur(), "goods stay at each variant's own price");
        eq("7.2000", levelled.lot(B).unitGoodsEur());
        eq("2.7000", levelled.lot(C).unitGoodsEur());
        assertEquals(apart.lot(C).logisticsKey(), levelled.lot(C).logisticsKey(), "outside the family nothing moves");
        assertEquals(List.of(), levelled.notes());
    }

    /* ---- scenarios ---- */

    /** Container PO-2026-014 of the contract; the deposit's bank euro varies. */
    private static Scenario exampleOne(String depositBankEuro) {
        Scenario example = new Scenario();
        example.enrosed = "2000.00";
        example.line(A, 1000, 950, 20, "2.00", Currency.USD);
        example.line(B, 500, 500, 0, "4.00", Currency.USD);
        example.brec(A, "0", "300", "120", "200", "0");
        example.brec(B, "0", "400", "180", "250", "0");
        example.supplierPlanned = "3680.00";
        example.logisticsPlanned = "1450.00";
        example.separatePlanned = "180.00";
        example.pay(1L, SUPPLIER, SEP_1, "1200", Currency.USD, depositBankEuro);
        example.pay(2L, SUPPLIER, DEC_5, "2800", Currency.USD, "2604.00");
        example.pay(3L, LOGISTICS, NOV_20, "1000", Currency.EUR, "1000.00");
        example.pay(4L, SEPARATE, NOV_20, "180", Currency.EUR, "180.00");
        example.pay(5L, OTHER, NOV_20, "35", Currency.EUR, "35.00");
        example.credit(1L, Reason.PRICE, "100", Currency.USD);
        example.credit(2L, Reason.SHORTAGE, "100", Currency.USD);
        return example;
    }

    /** Container PO-2026-021 of the contract, without the accrual decision. */
    private static Scenario exampleTwo() {
        Scenario example = new Scenario();
        example.goodsRate = example.transportRate = bd("0.90");
        example.receivedOn = example.rateCutoff = LocalDate.of(2026, 12, 10);
        example.cif = true;
        example.allocSeparate = Allocation.PIECES;
        example.line(C, 600, 600, 0, "50.00", Currency.CNY);
        example.line(D, 300, 310, 10, "100.00", Currency.CNY);
        example.brec(C, "300", "600", "260", "300", "98.90");
        example.brec(D, "180", "360", "355", "300", "51.10");
        example.supplierPlanned = "9000.00";
        example.supplierFreight = "1440.00";
        example.logisticsPlanned = "1200.00";
        example.separatePlanned = "150.00";
        example.pay(1L, SUPPLIER, LocalDate.of(2026, 10, 15), "30000", Currency.CNY, "3810.00");
        example.pay(2L, LOGISTICS, LocalDate.of(2026, 12, 10), "1200", Currency.EUR, "1200.00");
        example.pay(3L, SEPARATE, LocalDate.of(2026, 12, 10), "150", Currency.EUR, "150.00");
        example.loss.add(loss(C, StockMovement.Kind.DAMAGED, -6, "2026-12-20T09:00:00Z"));
        return example;
    }

    /** Lot B of the first example alone, paid 1.748,00 and settled, with or without the credit of 92,00. */
    private static Scenario lotB(boolean withCredit) {
        Scenario alone = new Scenario();
        alone.line(B, 500, 500, 0, "4.00", Currency.USD);
        alone.supplierPlanned = "1840.00";
        alone.pay(1L, SUPPLIER, NOV_20, "1748", Currency.EUR, "1748.00", true, null);
        if (withCredit) alone.credit(7L, Reason.PRICE, "92", Currency.EUR);
        return alone;
    }

    /** 1.000 ordered at 1,84, 950 arrived, the supplier paid 1.748,00 and settled. */
    private static Scenario shortAndSettled(BilledBasis billed, int damaged) {
        Scenario lower = new Scenario();
        lower.billedBasis = billed;
        lower.line(A, 1000, 950, damaged, "2.00", Currency.USD);
        lower.supplierPlanned = "1840.00";
        lower.pay(1L, SUPPLIER, NOV_20, "1748", Currency.EUR, "1748.00", true, null);
        return lower;
    }

    /** The container of the contract's two-closings case: balance paid on 05/01/2027, received on 20/01/2027. */
    private static Scenario twoClosings(PurchaseOrderStatus status, LocalDate receivedOn) {
        Scenario example = new Scenario();
        example.status = status;
        example.receivedOn = receivedOn;
        example.line(A, 1000, 1000, 0, "2.00", Currency.USD);
        example.line(B, 500, 500, 0, "4.00", Currency.USD);
        example.brec(A, "0", "300", "120", "200", "0");
        example.brec(B, "0", "400", "180", "250", "0");
        example.supplierPlanned = "3680.00";
        example.logisticsPlanned = "1450.00";
        example.pay(1L, SUPPLIER, SEP_1, "1200", Currency.USD, "1116.00");
        example.pay(2L, SUPPLIER, LocalDate.of(2027, 1, 5), "2800", Currency.USD, "2604.00");
        return example;
    }

    /** Goods 1.800,00, transport before the border 500,00, duty and arrival 250,00: paid to the forwarder or CIF. */
    private static Scenario sameMoney(boolean cif) {
        Scenario same = new Scenario();
        same.goodsRate = same.transportRate = bd("0.90");
        same.cif = cif;
        same.line(A, 100, 97, 0, "10.00", Currency.USD);
        same.line(B, 200, 200, 0, "5.00", Currency.USD);
        same.brec(A, "40", "160", "60", "40", "0");
        same.brec(B, "60", "240", "90", "60", "0");
        same.supplierPlanned = cif ? "2300.00" : "1800.00";
        same.supplierFreight = cif ? "500.00" : "0.00";
        same.logisticsPlanned = cif ? "250.00" : "750.00";
        same.pay(1L, SUPPLIER, SEP_1, cif ? "2300" : "1800", Currency.EUR, cif ? "2300.00" : "1800.00");
        same.pay(2L, LOGISTICS, SEP_1, cif ? "250" : "750", Currency.EUR, cif ? "250.00" : "750.00");
        return same;
    }

    /**
     * One container as the service would load it. The streams come from the
     * real reconciliation on the payments at the container rates, so every
     * status and remaining amount here is what production would hand over.
     */
    private static final class Scenario {
        BigDecimal cnyToUsd = bd("0.14");
        BigDecimal goodsRate = bd("0.92");
        BigDecimal transportRate = bd("0.92");
        String freightUsd = "0";
        String destinationCosts = "0";
        PurchaseOrderStatus status = PurchaseOrderStatus.ONTVANGEN;
        LocalDate receivedOn = NOV_20;
        PaymentTerms terms;
        Boolean cif;
        Boolean groupVariants = false;
        Allocation allocOrigin = Allocation.CBM;
        Allocation allocFreight = Allocation.CBM;
        Allocation allocDestination = Allocation.CBM;
        Allocation allocExtra = Allocation.PIECES;
        Allocation allocSeparate;
        String supplierPlanned = "0.00";
        String supplierFreight = "0.00";
        String logisticsPlanned = "0.00";
        String separatePlanned = "0.00";
        String enrosed = "0.00";
        /** The calculation on received quantities when a test runs the real one; otherwise built from brecLines. */
        LandedCost calculation;
        final List<PurchaseOrderLine> lines = new ArrayList<>();
        final Map<Long, Product> products = new LinkedHashMap<>();
        final List<String[]> brecLines = new ArrayList<>();
        final List<PurchasePayment> payments = new ArrayList<>();
        final List<PurchaseSupplierCredit> credits = new ArrayList<>();
        final List<StockMovement> loss = new ArrayList<>();
        final Map<Payee, Accrual> accruals = new EnumMap<>(Payee.class);
        final Map<Long, CreditTreatment> treatments = new LinkedHashMap<>();
        LocalDate rateCutoff = NOV_20;
        LocalDate costCutoff;
        QuantityBasis quantityBasis = QuantityBasis.ONTVANGEN;
        BilledBasis billedBasis = BilledBasis.BESTELD;
        Instant lossCutoff = Instant.parse("2026-12-31T23:00:00Z");
        private List<PurchaseReconciliation.Stream> reconciled = List.of();

        void line(long product, int ordered, int received, int damaged, String price, Currency currency) {
            products.putIfAbsent(product, product(product, null));
            lines.add(new PurchaseOrderLine((long) lines.size() + 1, product, received, bd(price), currency, null,
                    ordered, PriceBasis.EXW, damaged));
        }

        void ddpLine(long product, int ordered, int received, String price, Currency currency) {
            products.putIfAbsent(product, product(product, null));
            lines.add(new PurchaseOrderLine((long) lines.size() + 1, product, received, bd(price), currency, null,
                    ordered, PriceBasis.DDP, 0));
        }

        /** What the calculation on received quantities holds for one product. */
        void brec(long product, String origin, String freight, String duty, String destination, String separate) {
            brecLines.add(new String[] {Long.toString(product), origin, freight, duty, destination, separate});
        }

        void pay(long id, Payee payee, LocalDate on, String amount, Currency currency, String bankEuro) {
            pay(id, payee, on, amount, currency, bankEuro, false, null);
        }

        void pay(long id, Payee payee, LocalDate on, String amount, Currency currency, String bankEuro,
                 boolean settles, PaymentTerms.Moment due) {
            payments.add(payment(id, payee, on, amount, currency, bankEuro, settles, due));
        }

        /** The stored euro is deliberately off: a credit counts at the order's rate. */
        void credit(long id, Reason reason, String amount, Currency currency) {
            credits.add(new PurchaseSupplierCredit(id, 14L, NOV_20, bd(amount), currency,
                    currency == Currency.EUR ? bd(amount) : bd(amount).multiply(bd("0.80")).setScale(2),
                    reason, null, PurchaseSupplierCredit.Status.OPEN, null, null, null, "test", null));
        }

        PurchaseOrder order() {
            return new PurchaseOrder(14L, "PO-2026-014", null, 1L, LocalDate.of(2026, 8, 1), status,
                    ContainerType.FORTY_HQ, cnyToUsd, goodsRate, transportRate,
                    bd(freightUsd), BigDecimal.ZERO, Currency.USD, bd(destinationCosts),
                    BigDecimal.ZERO, bd(enrosed), allocFreight, allocOrigin, allocDestination, allocExtra,
                    "Ningbo", "Rotterdam", null, groupVariants, null, receivedOn, null, null, terms, null, null,
                    null, null, null, List.copyOf(lines), null, List.of(), null, null, null, null,
                    allocSeparate, null, null, null, cif);
        }

        PurchaseReconciliation.Stream reconciled(Payee payee) {
            return reconciled.stream().filter(stream -> stream.payee() == payee).findFirst().orElseThrow();
        }

        LotCost.Container run() {
            PurchaseOrder order = order();
            LandedCost received = calculation != null ? calculation : handBuilt();
            var payable = new PurchaseOrderService.Payable(bd(supplierPlanned), bd(logisticsPlanned), bd(enrosed),
                    order.cif(), false, bd(supplierFreight));
            LandedCost budget = new LandedCost(received.lines(), totals(received.lines()), null);
            reconciled = new PurchaseReconciliationCalculator().calculate(order, budget, payable,
                    LotCostCalculator.normalised(order, payments), credits).streams();
            var options = new LotCost.Options(rateCutoff, quantityBasis, costCutoff, lossCutoff, billedBasis,
                    accruals, treatments);
            var container = new LotCostCalculator().calculate(new LotCost.Input(order, options, products, received,
                    bd(supplierPlanned), bd(supplierFreight), payments, credits, reconciled, loss));
            assertAddsUp(container);
            return container;
        }

        /** The Enrosed kost sits on every line and in every total the lot cost must not read. */
        private LandedCost handBuilt() {
            List<LandedCost.Line> built = new ArrayList<>();
            BigDecimal share = brecLines.isEmpty() ? BigDecimal.ZERO
                    : bd(enrosed).divide(BigDecimal.valueOf(brecLines.size()), 2, RoundingMode.HALF_UP);
            for (String[] line : brecLines) {
                BigDecimal external = bd(line[1]).add(bd(line[2])).add(bd(line[3])).add(bd(line[4])).add(bd(line[5]));
                built.add(new LandedCost.Line(Long.valueOf(line[0]), "Product " + line[0], 1, 0, bd("0"),
                        bd("0"), bd("0"), bd(line[1]), bd(line[2]), bd("0"), bd("6.5"), "HS", bd(line[3]),
                        bd(line[4]), share, external.add(share), external.add(share), bd("0"), bd("0"), bd("0"),
                        bd(line[5])));
            }
            return new LandedCost(built, totals(built), null);
        }

        private LandedCost.Totals totals(List<LandedCost.Line> built) {
            boolean inside = allocSeparate != null && allocSeparate != Allocation.SEPARATE;
            return new LandedCost.Totals(0, 0, bd("0"), bd("0"), bd(supplierPlanned), bd("0"), bd("0"), bd("0"),
                    bd("0"), bd("0"), bd(enrosed), bd("0"), bd("0"), bd("0"), bd(separatePlanned), List.of(),
                    bd("0"), bd(separatePlanned), bd("0"), inside);
        }
    }

    /* ---- helpers ---- */

    /**
     * Every share follows from its stored key within one cent, the shares add up to
     * the stream totals and the unit components add up to the unit value.
     */
    private static void assertAddsUp(LotCost.Container container) {
        var supplier = container.stream(SUPPLIER);
        assertEquals(supplier.includedEur(), container.supplierGoodsEur().add(container.supplierTransportEur()));
        assertShares(container, container.supplierGoodsEur(), LotCost.Lot::goodsKey, LotCost.Lot::goodsEur);
        assertShares(container, container.priceCreditEur(), LotCost.Lot::goodsKey, LotCost.Lot::priceCreditEur);
        assertShares(container, container.stream(LOGISTICS).includedEur(), LotCost.Lot::logisticsKey, LotCost.Lot::logisticsEur);
        assertShares(container, container.stream(SEPARATE).includedEur(), LotCost.Lot::separateKey, LotCost.Lot::separateEur);
        if (container.cif()) {
            assertShares(container, container.supplierTransportEur(), LotCost.Lot::transportKey, LotCost.Lot::transportEur);
        } else {
            eq("0.00", sum(container, LotCost.Lot::transportEur));
        }
        if (!container.lots().isEmpty()) {
            assertEquals(container.acquisitionEur(), sum(container, LotCost.Lot::lotCostEur));
            assertEquals(container.estimatedEur(), sum(container, LotCost.Lot::estimatedEur));
        }
        for (LotCost.Lot lot : container.lots()) {
            assertEquals(lot.unitValueEur(), lot.unitGoodsEur().add(lot.unitTransportEur())
                    .add(lot.unitLogisticsEur()).add(lot.unitSeparateEur()), "components of product " + lot.productId());
            assertEquals(4, lot.unitValueEur().scale());
            assertEquals(lot.lotCostEur(), lot.goodsEur().subtract(lot.priceCreditEur()).add(lot.transportEur())
                    .add(lot.logisticsEur()).add(lot.separateEur()));
            assertEquals(lot.logisticsEur().add(lot.transportEur()), lot.calcOriginEur().add(lot.calcFreightEur())
                    .add(lot.calcDutyEur()).add(lot.calcDestinationEur()));
        }
    }

    private static void assertShares(LotCost.Container container, BigDecimal amount,
                                     Function<LotCost.Lot, BigDecimal> key, Function<LotCost.Lot, BigDecimal> share) {
        if (container.lots().isEmpty()) return;
        assertEquals(amount, sum(container, share), "every cent is assigned once");
        BigDecimal keys = sum(container, key);
        if (keys.signum() == 0) return;
        for (LotCost.Lot lot : container.lots()) {
            BigDecimal exact = amount.multiply(key.apply(lot)).divide(keys, 6, RoundingMode.HALF_UP);
            assertTrue(exact.subtract(share.apply(lot)).abs().compareTo(new BigDecimal("0.01")) < 0,
                    "share " + share.apply(lot) + " of product " + lot.productId() + " against " + exact);
        }
    }

    private static BigDecimal sum(LotCost.Container container, Function<LotCost.Lot, BigDecimal> field) {
        return container.lots().stream().map(field).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    /** The estimated transport of a CIF container: its estimate times the transport part of the supplier split. */
    private static BigDecimal estimatedTransport(LotCost.Container container) {
        return container.stream(SUPPLIER).estimatedEur().multiply(new BigDecimal("1440"))
                .divide(new BigDecimal("9000"), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal perPiece(BigDecimal key, int pieces) {
        return key.divide(BigDecimal.valueOf(pieces), 6, RoundingMode.HALF_UP);
    }

    private static PaymentUse payment(LotCost.Container container, long id) {
        return container.payments().stream().filter(use -> use.paymentId() == id).findFirst().orElseThrow();
    }

    private static CreditUse credit(LotCost.Container container, long id) {
        return container.credits().stream().filter(use -> use.creditId() == id).findFirst().orElseThrow();
    }

    private static PurchasePayment payment(long id, Payee payee, LocalDate on, String amount, Currency currency,
                                           String bankEuro, boolean settles, PaymentTerms.Moment due) {
        return new PurchasePayment(id, 14L, on, bd(amount), currency, bankEuro == null ? null : bd(bankEuro),
                "Betaling " + id, "test", null, payee, settles, due);
    }

    private static StockMovement loss(long product, StockMovement.Kind kind, int delta, String at) {
        return new StockMovement(null, product, 1L, Instant.parse(at), delta, 0, kind, "melding", "test", 14L);
    }

    /** A product whose own figures must never be read: its landed cost holds the Enrosed kost. */
    private static Product product(long id, Long familyId) {
        return new Product(id, "ENR-" + id, "Roos " + id,
                new Dimensions(new BigDecimal("30"), new BigDecimal("20"), new BigDecimal("30")),
                null, null, 1L, 1L, true, familyId, null, null, 0, true, null, null,
                PublicationState.DRAFT, PublicationState.DRAFT, Barcodes.none(), "0603.90.00",
                new Carton(new Dimensions(new BigDecimal("68"), new BigDecimal("50"), new BigDecimal("40")),
                        4, new BigDecimal("10")),
                null, null, null, new BigDecimal("999.9900"), "test", new BigDecimal("35"), null, 0,
                List.of(), List.of());
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static void eq(String expected, BigDecimal actual) { assertEquals(new BigDecimal(expected), actual); }
    private static void eq(String expected, BigDecimal actual, String message) {
        assertEquals(new BigDecimal(expected), actual, message);
    }
}
