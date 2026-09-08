package be.enrosed.finance.application;

import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.shared.BusinessRuleException;
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
        assertEquals(new BigDecimal("1512.51"), saved.amountInclEur());
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
}
