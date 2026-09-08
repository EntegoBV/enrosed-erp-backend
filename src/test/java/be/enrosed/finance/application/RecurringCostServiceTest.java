package be.enrosed.finance.application;

import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.RecurringCost;
import be.enrosed.finance.domain.RecurringCost.Interval;
import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecurringCostServiceTest {

    private RecurringCosts definitions;
    private CompanyCosts costs;
    private RecurringCostService service;
    private final List<CompanyCost> booked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        definitions = mock(RecurringCosts.class);
        costs = mock(CompanyCosts.class);
        AtomicLong ids = new AtomicLong(100);
        when(definitions.save(any(RecurringCost.class))).thenAnswer(call -> {
            RecurringCost definition = call.getArgument(0);
            return definition.id() == null ? definition.withProgress(definition.nextDate(), definition.lastBookedOn(), definition.active())
                    : definition;
        });
        when(costs.save(any(CompanyCost.class))).thenAnswer(call -> {
            CompanyCost cost = call.getArgument(0);
            CompanyCost saved = new CompanyCost(ids.incrementAndGet(), cost.date(), cost.category(), cost.description(), cost.party(),
                    cost.amountExclEur(), cost.vatPct(), cost.reference(), cost.paidOn(), cost.salesChannel(), cost.notes(),
                    cost.createdAt(), cost.recurringCostId());
            booked.add(saved);
            return saved;
        });
        when(costs.findByRecurringCost(anyLong())).thenReturn(List.of());
        service = new RecurringCostService(definitions, costs);
    }

    private static RecurringCost rent(Long id, Interval interval, LocalDate start, LocalDate end, LocalDate next, LocalDate lastBooked, boolean autoPaid) {
        return new RecurringCost(id, "Huur magazijn", "HUUR", "Immo Ham", new BigDecimal("850.00"), new BigDecimal("21"), null,
                interval, start, end, next, true, autoPaid, "HUUR-2026", null, lastBooked, Instant.parse("2026-01-01T09:00:00Z"));
    }

    @Test
    void settingUpAMonthlyCostStartsItOnItsFirstDate() {
        RecurringCost saved = service.create(new RecurringCost(null, " Huur magazijn ", "huur", "Immo Ham", new BigDecimal("850"),
                new BigDecimal("21"), null, Interval.MONTHLY, LocalDate.of(2026, 1, 31), null, null, true, false, null, " ", null, null));

        assertEquals("Huur magazijn", saved.name());
        assertEquals("HUUR", saved.category());
        assertEquals(new BigDecimal("850.00"), saved.amountExclEur());
        assertEquals(LocalDate.of(2026, 1, 31), saved.nextDate());
        assertTrue(saved.active());
        assertNull(saved.notes());
    }

    @Test
    void everyDueOccurrenceIsBookedAndTheMonthNeverDrifts() {
        RecurringCost definition = rent(7L, Interval.MONTHLY, LocalDate.of(2026, 1, 31), null, LocalDate.of(2026, 1, 31), null, false);
        when(definitions.findAll()).thenReturn(List.of(definition));

        List<CompanyCost> result = service.bookDue(LocalDate.of(2026, 4, 10));

        assertEquals(List.of(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)),
                result.stream().map(CompanyCost::date).toList(), "February shortens the day, March gets the 31st back");
        CompanyCost first = result.get(0);
        assertEquals("Huur magazijn", first.description());
        assertEquals("HUUR", first.category());
        assertEquals(new BigDecimal("850.00"), first.amountExclEur());
        assertEquals(7L, first.recurringCostId());
        assertNull(first.paidOn(), "not a direct debit, so the cost stays open");
        assertTrue(first.notes().contains("maandelijks"));

        ArgumentCaptor<RecurringCost> progress = ArgumentCaptor.forClass(RecurringCost.class);
        verify(definitions).save(progress.capture());
        assertEquals(LocalDate.of(2026, 4, 30), progress.getValue().nextDate());
        assertEquals(LocalDate.of(2026, 3, 31), progress.getValue().lastBookedOn());
        assertTrue(progress.getValue().active());
    }

    @Test
    void aDirectDebitIsBookedAsPaidOnItsDay() {
        RecurringCost definition = rent(3L, Interval.WEEKLY, LocalDate.of(2026, 9, 1), null, LocalDate.of(2026, 9, 1), null, true);
        when(definitions.findAll()).thenReturn(List.of(definition));

        List<CompanyCost> result = service.bookDue(LocalDate.of(2026, 9, 8));

        assertEquals(2, result.size());
        assertEquals(LocalDate.of(2026, 9, 1), result.get(0).paidOn());
        assertEquals(LocalDate.of(2026, 9, 8), result.get(1).paidOn());
    }

    @Test
    void nothingIsBookedBeforeItsDayOrTwice() {
        RecurringCost definition = rent(5L, Interval.QUARTERLY, LocalDate.of(2026, 10, 1), null, LocalDate.of(2026, 10, 1), null, false);
        when(definitions.findAll()).thenReturn(List.of(definition));
        assertTrue(service.bookDue(LocalDate.of(2026, 9, 30)).isEmpty());
        verify(costs, never()).save(any());

        when(costs.findByRecurringCost(5L)).thenReturn(List.of(new CompanyCost(9L, LocalDate.of(2026, 10, 1), "HUUR", "Huur magazijn",
                null, new BigDecimal("850.00"), null, null, null, null, null, null, 5L)));
        assertTrue(service.bookDue(LocalDate.of(2026, 10, 1)).isEmpty(), "the first of October is already in the books");
        ArgumentCaptor<RecurringCost> progress = ArgumentCaptor.forClass(RecurringCost.class);
        verify(definitions).save(progress.capture());
        assertEquals(LocalDate.of(2027, 1, 1), progress.getValue().nextDate(), "the schedule still moves on");
    }

    @Test
    void aScheduleWithAnEndDateStopsAndGoesInactive() {
        RecurringCost definition = rent(4L, Interval.MONTHLY, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 1, 15), null, false);
        when(definitions.findAll()).thenReturn(List.of(definition));

        List<CompanyCost> result = service.bookDue(LocalDate.of(2026, 12, 1));

        assertEquals(3, result.size());
        ArgumentCaptor<RecurringCost> progress = ArgumentCaptor.forClass(RecurringCost.class);
        verify(definitions).save(progress.capture());
        assertNull(progress.getValue().nextDate());
        assertFalse(progress.getValue().active());
        assertEquals(LocalDate.of(2026, 3, 15), progress.getValue().lastBookedOn());
    }

    @Test
    void anInactiveDefinitionIsLeftAlone() {
        RecurringCost paused = rent(8L, Interval.MONTHLY, LocalDate.of(2026, 1, 1), null, LocalDate.of(2026, 1, 1), null, false)
                .withProgress(LocalDate.of(2026, 1, 1), null, false);
        when(definitions.findAll()).thenReturn(List.of(paused));
        assertTrue(service.bookDue(LocalDate.of(2026, 6, 1)).isEmpty());
        verify(costs, never()).save(any());
    }

    @Test
    void updatingContinuesAfterTheLastBookingInsteadOfStartingOver() {
        RecurringCost current = rent(6L, Interval.MONTHLY, LocalDate.of(2026, 1, 5), null, LocalDate.of(2026, 4, 5),
                LocalDate.of(2026, 3, 5), false);
        when(definitions.findById(6L)).thenReturn(Optional.of(current));

        RecurringCost updated = service.update(6L, new RecurringCost(null, "Huur magazijn", "HUUR", "Immo Ham", new BigDecimal("900"),
                new BigDecimal("21"), null, Interval.MONTHLY, LocalDate.of(2026, 1, 5), null, LocalDate.of(2026, 1, 5), true, false,
                null, null, null, null));

        assertEquals(6L, updated.id());
        assertEquals(new BigDecimal("900.00"), updated.amountExclEur());
        assertEquals(LocalDate.of(2026, 4, 5), updated.nextDate(), "the form cannot rewind the schedule");
        assertEquals(LocalDate.of(2026, 3, 5), updated.lastBookedOn());
        assertEquals(current.createdAt(), updated.createdAt());
    }

    @Test
    void aDefinitionNeedsANameACategoryARhythmAndAFirstDate() {
        assertThrows(BusinessRuleException.class, () -> service.create(new RecurringCost(null, " ", "HUUR", null, BigDecimal.TEN, null, null,
                Interval.MONTHLY, LocalDate.of(2026, 1, 1), null, null, true, false, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new RecurringCost(null, "Huur", "", null, BigDecimal.TEN, null, null,
                Interval.MONTHLY, LocalDate.of(2026, 1, 1), null, null, true, false, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new RecurringCost(null, "Huur", "HUUR", null, BigDecimal.TEN, null, null,
                null, LocalDate.of(2026, 1, 1), null, null, true, false, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new RecurringCost(null, "Huur", "HUUR", null, BigDecimal.TEN, null, null,
                Interval.MONTHLY, null, null, null, true, false, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new RecurringCost(null, "Huur", "HUUR", null, new BigDecimal("-1"), null, null,
                Interval.MONTHLY, LocalDate.of(2026, 1, 1), null, null, true, false, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new RecurringCost(null, "Huur", "HUUR", null, BigDecimal.TEN, null, null,
                Interval.MONTHLY, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), null, true, false, null, null, null, null)));
    }
}
