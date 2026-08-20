package be.enrosed.sales.application;

import be.enrosed.sales.domain.DeliveryTermsState;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.FreightPricingStrategy;
import be.enrosed.sales.domain.LoadMode;
import be.enrosed.sales.domain.MarkupMode;
import be.enrosed.sales.domain.PalletProfile;
import be.enrosed.sales.domain.QuoteStatus;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SalesLifecycleTest {

    @Test
    void fullAggregateCanOnlyBeEditedAsConcept() {
        assertDoesNotThrow(() -> SalesLifecycle.requireEditable(order(QuoteStatus.CONCEPT)));
        assertThrows(BusinessRuleException.class,
                () -> SalesLifecycle.requireEditable(order(QuoteStatus.VERZONDEN)));
        assertThrows(BusinessRuleException.class,
                () -> SalesLifecycle.requireEditable(order(QuoteStatus.GEACCEPTEERD)));
    }

    @Test
    void sendAllowsDraftAndOpenResendButNeverFinalOrPendingRevision() {
        assertDoesNotThrow(() -> SalesLifecycle.requireSendable(order(QuoteStatus.CONCEPT)));
        assertDoesNotThrow(() -> SalesLifecycle.requireSendable(order(QuoteStatus.VERZONDEN)));
        assertDoesNotThrow(() -> SalesLifecycle.requireSendable(order(QuoteStatus.BEKEKEN)));

        for (QuoteStatus blocked : List.of(QuoteStatus.WIJZIGING_GEVRAAGD,
                QuoteStatus.GEACCEPTEERD, QuoteStatus.AFGEWEZEN, QuoteStatus.VERLOPEN)) {
            assertThrows(BusinessRuleException.class,
                    () -> SalesLifecycle.requireSendable(order(blocked)), blocked.name());
        }
    }

    @Test
    void onlyNeverSentUnusedConceptCanBeDeleted() {
        assertDoesNotThrow(() -> SalesLifecycle.requireDeletable(order(QuoteStatus.CONCEPT), false));
        assertThrows(BusinessRuleException.class,
                () -> SalesLifecycle.requireDeletable(sentConcept(), false));
        assertThrows(BusinessRuleException.class,
                () -> SalesLifecycle.requireDeletable(order(QuoteStatus.CONCEPT), true));
        assertThrows(BusinessRuleException.class,
                () -> SalesLifecycle.requireDeletable(order(QuoteStatus.VERZONDEN), false));
    }

    @Test
    void portalFailsClosedWhileReopenedAggregateIsDraft() {
        assertThrows(BusinessRuleException.class,
                () -> SalesLifecycle.requirePortalVisible(sentConcept()));
        assertDoesNotThrow(() -> SalesLifecycle.requirePortalVisible(order(QuoteStatus.VERZONDEN)));
    }

    @Test
    void narrowDeliveryAndFreightFlowRemainsAvailableForOpenQuote() {
        assertDoesNotThrow(() -> SalesLifecycle.requireTermsEditable(order(QuoteStatus.CONCEPT)));
        assertDoesNotThrow(() -> SalesLifecycle.requireTermsEditable(order(QuoteStatus.VERZONDEN)));
        assertDoesNotThrow(() -> SalesLifecycle.requireTermsEditable(order(QuoteStatus.BEKEKEN)));
        assertThrows(BusinessRuleException.class,
                () -> SalesLifecycle.requireTermsEditable(order(QuoteStatus.GEACCEPTEERD)));
    }

    private static SalesOrder order(QuoteStatus status) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(1L, "ENR-TEST", 2L, "BE", today, today.plusDays(30),
                status, "DAP", null, null, MarkupMode.PRODUCT, BigDecimal.ZERO,
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null,
                List.of(), List.of());
    }

    private static SalesOrder sentConcept() {
        SalesOrder draft = order(QuoteStatus.CONCEPT);
        return new SalesOrder(draft.id(), draft.number(), draft.customerId(), draft.countryCode(),
                draft.orderDate(), draft.validUntil(), draft.status(), draft.incoterm(),
                draft.paymentTerms(), draft.notes(), draft.markupMode(), draft.orderMarkupPct(),
                draft.extraDiscountPct(), draft.extraDiscountLabel(), "existing-token", Instant.now(),
                null, 0, null, null, null, null, draft.deliveryTerms(), draft.freight(),
                draft.manualFreightEur(), draft.loadMode(), draft.palletProfile(),
                draft.maxPalletHeightCm(), draft.freightPricingStrategy(),
                draft.freightRatePerCbmEur(), draft.lines(), draft.pallets());
    }
}
