package be.enrosed.sourcing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The plans a supplier can be paid in: the presets, and a split of one's own under CUSTOM. */
class PaymentPlanTest {

    @Test
    void theNewPresetsSayWhenTheRestFallsDue() {
        List<PaymentTerms.Instalment> plan = PaymentTerms.THIRD_TWO_THIRDS_SHIPPED.instalments();
        assertEquals(2, plan.size());
        assertEquals("2/3 bij vertrek", plan.get(1).label());
        assertEquals(PaymentTerms.Moment.SHIPPED, plan.get(1).due());
        assertEquals(new BigDecimal("0.666667"), plan.get(1).share());
        assertEquals(PaymentTerms.Moment.ARRIVED, PaymentTerms.THIRD_TWO_THIRDS_ARRIVED.instalments().get(1).due());
        assertEquals("30% bij bestelling, 70% bij aankomst", PaymentTerms.DEPOSIT_30_70_ARRIVED.dutchLabel());
        assertEquals("50% bij aankomst", PaymentTerms.HALF_HALF_ARRIVED.instalments().get(1).label());
    }

    @Test
    void aSplitOfOnesOwnSkipsTheMomentsWithNothingToPayAndReadsAsWords() {
        List<PaymentTerms.Instalment> plan = PaymentTerms.split(new BigDecimal("40"), BigDecimal.ZERO, new BigDecimal("60"));
        assertEquals(2, plan.size());
        assertEquals("40% bij bestelling", plan.get(0).label());
        assertEquals(new BigDecimal("0.400000"), plan.get(0).share());
        assertEquals("60% bij aankomst", plan.get(1).label());
        assertEquals("40% bij bestelling, 60% bij aankomst", PaymentTerms.splitLabel(new BigDecimal("40"), null, new BigDecimal("60")));
        assertEquals("Anders (vrij)", PaymentTerms.splitLabel(null, null, null), "nothing entered yet");
    }

    @Test
    void anOrderUnderCustomTermsPaysByItsOwnSplitAndOtherwiseByThePreset() {
        PurchaseOrder base = new PurchaseOrder(
                1L, "PO-1", null, 1L, java.time.LocalDate.of(2026, 9, 1), PurchaseOrderStatus.BESTELD,
                ContainerType.FORTY_HQ, new BigDecimal("0.14"), new BigDecimal("0.89"), new BigDecimal("0.89"),
                null, null, be.enrosed.shared.Currency.USD, null, null, null,
                Allocation.CBM, Allocation.VALUE, Allocation.CBM, Allocation.VALUE,
                null, null, null, null, null, null, null, null,
                PaymentTerms.CUSTOM, null, null, null, null, "", List.of());
        assertTrue(base.paymentInstalments().isEmpty(), "CUSTOM without percentages has nothing planned");
        PurchaseOrder split = base.withPaymentSplit(new BigDecimal("25"), new BigDecimal("25"), new BigDecimal("50"));
        assertEquals(3, split.paymentInstalments().size());
        assertEquals("25% bij bestelling, 25% bij vertrek, 50% bij aankomst", split.paymentTermsLabel());
        PurchaseOrder preset = split.withReceipt(PurchaseOrderStatus.BESTELD, null, null, null, "", List.of());
        assertEquals(3, preset.paymentInstalments().size(), "the split survives every with-copy");
    }
}
