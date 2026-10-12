package be.enrosed.finance.application;

import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyCostServiceTest {

    private CompanyCosts costs;
    private CompanyCostService service;

    @BeforeEach
    void setUp() {
        costs = mock(CompanyCosts.class);
        when(costs.save(any(CompanyCost.class))).thenAnswer(call -> call.getArgument(0));
        service = new CompanyCostService(costs);
    }

    @Test
    void bookingACostCleansItAndComputesTheVat() {
        CompanyCost saved = service.create(new CompanyCost(null, LocalDate.of(2026, 10, 3), " beurs ", "Stand TICA oktober",
                " TICA Trends & Trade ", new BigDecimal("1250.005"), new BigDecimal("21"), "F-2026-77", null, "tica", "", null));

        assertEquals("BEURS", saved.category());
        assertEquals("TICA Trends & Trade", saved.party());
        assertEquals(new BigDecimal("1250.01"), saved.amountExclEur());
        assertEquals(new BigDecimal("262.50"), saved.vatEur());
        assertEquals(new BigDecimal("1512.51"), saved.totalInclEur());
        assertNull(saved.amountInclEur(), "entered excl.: no inclusive total is stored");
        assertEquals("TICA", saved.salesChannel());
        assertNull(saved.notes(), "blank notes are stored as nothing");
        assertNotNull(saved.createdAt());
    }

    @Test
    void aCostNeedsADateACategoryADescriptionAndANonNegativeAmount() {
        assertThrows(BusinessRuleException.class, () -> service.create(new CompanyCost(null, null, "HUUR", "Magazijn",
                null, BigDecimal.TEN, null, null, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new CompanyCost(null, LocalDate.now(), " ", "Magazijn",
                null, BigDecimal.TEN, null, null, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new CompanyCost(null, LocalDate.now(), "HUUR", "",
                null, BigDecimal.TEN, null, null, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new CompanyCost(null, LocalDate.now(), "HUUR", "Magazijn",
                null, new BigDecimal("-1"), null, null, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new CompanyCost(null, LocalDate.now(), "HUUR", "Magazijn",
                null, BigDecimal.TEN, new BigDecimal("120"), null, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.list(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)));
    }

    @Test
    void updatingKeepsTheIdentityAndTheBookingMoment() {
        CompanyCost existing = new CompanyCost(9L, LocalDate.of(2026, 1, 5), "BOEKHOUDER", "Jaarrekening", "Accountant BV",
                new BigDecimal("900.00"), new BigDecimal("21"), null, null, null, null, java.time.Instant.parse("2026-01-05T10:00:00Z"));
        when(costs.findById(9L)).thenReturn(Optional.of(existing));

        CompanyCost updated = service.update(9L, new CompanyCost(null, LocalDate.of(2026, 1, 6), "BOEKHOUDER", "Jaarrekening 2025",
                "Accountant BV", new BigDecimal("950"), new BigDecimal("21"), "2026-014", LocalDate.of(2026, 1, 20), null, null, null));

        assertEquals(9L, updated.id());
        assertEquals(existing.createdAt(), updated.createdAt());
        assertEquals(LocalDate.of(2026, 1, 20), updated.paidOn());
        assertEquals(new BigDecimal("950.00"), updated.amountExclEur());

        service.delete(9L);
        verify(costs).deleteById(9L);
    }

    @Test
    void markingPaidKeepsEverythingElseIncludingTheRecurringLink() {
        CompanyCost booked = new CompanyCost(12L, LocalDate.of(2026, 9, 1), "HUUR", "Huur magazijn", "Immo Ham",
                new BigDecimal("850.00"), new BigDecimal("21"), null, null, null, "Automatisch geboekt", Instant.parse("2026-09-01T06:00:00Z"), 3L);
        when(costs.findById(12L)).thenReturn(Optional.of(booked));

        CompanyCost paid = service.markPaid(12L, LocalDate.of(2026, 9, 4));

        assertEquals(LocalDate.of(2026, 9, 4), paid.paidOn());
        assertEquals(3L, paid.recurringCostId());
        assertEquals(booked.createdAt(), paid.createdAt());

        CompanyCost edited = service.update(12L, new CompanyCost(null, LocalDate.of(2026, 9, 1), "HUUR", "Huur magazijn september",
                "Immo Ham", new BigDecimal("850"), new BigDecimal("21"), null, null, null, null, null, null));
        assertEquals(3L, edited.recurringCostId(), "editing the text does not cut the cost loose from its definition");
    }

    @Test
    void aCostEnteredInclusiveKeepsItsTotalAndTakesTheVatFromIt() {
        CompanyCost saved = service.create(cost("82.64", "100", "21"));

        assertEquals(new BigDecimal("100.00"), saved.amountInclEur());
        assertEquals(new BigDecimal("82.64"), saved.amountExclEur());
        assertEquals(new BigDecimal("17.36"), saved.vatEur(), "what the invoice shows, not 82.64 × 21 % = 17.35");
        assertEquals(new BigDecimal("100.00"), saved.totalInclEur());

        CompanyCost existing = new CompanyCost(4L, LocalDate.of(2026, 10, 1), "HUUR", "Magazijn", "Immo",
                new BigDecimal("50.00"), null, null, null, null, null, null, Instant.parse("2026-10-01T06:00:00Z"), null);
        when(costs.findById(4L)).thenReturn(Optional.of(existing));
        CompanyCost updated = service.update(4L, cost("82.64", "100", "21"));
        assertEquals(new BigDecimal("100.00"), updated.amountInclEur(), "an edit carries the entered total through");
        when(costs.findById(4L)).thenReturn(Optional.of(updated));
        assertEquals(new BigDecimal("100.00"), service.markPaid(4L, LocalDate.of(2026, 10, 12)).amountInclEur(),
                "settling the cost keeps the entered total");
    }

    @Test
    void anInclusiveTotalSitsAboveExclAndMatchesTheRate() {
        assertThrows(BusinessRuleException.class, () -> service.create(cost("100", "90", "21")), "below excl.");
        assertThrows(BusinessRuleException.class, () -> service.create(cost("82.64", "110", "21")), "far from excl. × 21 %");
        assertThrows(BusinessRuleException.class, () -> service.create(cost("82.64", "82.65", null)), "without VAT incl. equals excl.");
        assertThrows(BusinessRuleException.class, () -> service.create(cost("82.64", "82.65", "0")), "at 0 % incl. equals excl.");
        assertEquals(new BigDecimal("82.64"), service.create(cost("82.64", "82.64", null)).amountInclEur());
        assertEquals(new BigDecimal("100.01"), service.create(cost("82.65", "100.01", "21")).amountInclEur(), "a cent of rounding fits");
        assertEquals(new BigDecimal("100.00"), service.create(cost("82.64", "99.995", "21")).amountInclEur(), "scaled to cents");
    }

    @Test
    void aCostEnteredExclusiveStaysWithoutAStoredTotal() {
        CompanyCost saved = service.create(cost("82.64", null, "21"));

        assertNull(saved.amountInclEur());
        assertEquals(new BigDecimal("17.35"), saved.vatEur());
        assertEquals(new BigDecimal("99.99"), saved.totalInclEur());
    }

    @Test
    void bookingACostTeachesTheListAanWieTheNameAndItsDefaults() {
        CostPartyService learner = mock(CostPartyService.class);
        @SuppressWarnings("unchecked") Instance<CostPartyService> parties = mock(Instance.class);
        when(parties.isResolvable()).thenReturn(true);
        when(parties.get()).thenReturn(learner);
        service.parties = parties;

        service.create(new CompanyCost(null, LocalDate.of(2026, 10, 3), " beurs ", "Stand TICA oktober",
                " TICA Trends & Trade ", BigDecimal.TEN, new BigDecimal("21"), null, null, "tica", null, null));

        verify(learner).learn("TICA Trends & Trade", "BEURS", new BigDecimal("21"), "TICA");
    }

    private static CompanyCost cost(String excl, String incl, String vat) {
        return new CompanyCost(null, LocalDate.of(2026, 10, 12), "HUUR", "Magazijn", "Immo", new BigDecimal(excl),
                incl == null ? null : new BigDecimal(incl), vat == null ? null : new BigDecimal(vat),
                null, null, null, null, null, null);
    }
}
