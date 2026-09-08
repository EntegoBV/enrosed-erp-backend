package be.enrosed.finance.application;

import be.enrosed.finance.domain.BankBalance;
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

class BankBalanceServiceTest {

    private BankBalances balances;
    private BankBalanceService service;

    @BeforeEach
    void setUp() {
        balances = mock(BankBalances.class);
        when(balances.save(any(BankBalance.class))).thenAnswer(call -> call.getArgument(0));
        service = new BankBalanceService(balances);
    }

    @Test
    void aReadingIsCleanedAndRoundedToCents() {
        BankBalance saved = service.create(new BankBalance(null, " KBC zichtrekening ", LocalDate.of(2026, 9, 8),
                new BigDecimal("12345.678"), "  ", null));

        assertEquals("KBC zichtrekening", saved.account());
        assertEquals(new BigDecimal("12345.68"), saved.balanceEur());
        assertNull(saved.notes());
        assertNotNull(saved.createdAt());
    }

    @Test
    void aNegativeBalanceIsAllowedButAccountDateAndAmountAreNot() {
        BankBalance overdrawn = service.create(new BankBalance(null, "Kredietlijn", LocalDate.of(2026, 9, 8), new BigDecimal("-2500"), null, null));
        assertEquals(new BigDecimal("-2500.00"), overdrawn.balanceEur());
        assertThrows(BusinessRuleException.class, () -> service.create(new BankBalance(null, " ", LocalDate.of(2026, 9, 8), BigDecimal.TEN, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new BankBalance(null, "KBC", null, BigDecimal.TEN, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.create(new BankBalance(null, "KBC", LocalDate.of(2026, 9, 8), null, null, null)));
    }

    @Test
    void updatingKeepsTheIdentityAndTheMomentItWasEntered() {
        BankBalance existing = new BankBalance(4L, "KBC", LocalDate.of(2026, 9, 1), new BigDecimal("100.00"), null,
                Instant.parse("2026-09-01T08:00:00Z"));
        when(balances.findById(4L)).thenReturn(Optional.of(existing));

        BankBalance updated = service.update(4L, new BankBalance(null, "KBC zicht", LocalDate.of(2026, 9, 2), new BigDecimal("150"), null, null));

        assertEquals(4L, updated.id());
        assertEquals(existing.createdAt(), updated.createdAt());
        assertEquals(new BigDecimal("150.00"), updated.balanceEur());

        service.delete(4L);
        verify(balances).deleteById(4L);
    }

    @Test
    void exactCheckpointKeepsTheBankInstantAndItsLocalCalendarDate() {
        Instant moment = Instant.parse("2026-09-07T22:15:30.456Z");
        BankBalance saved = service.create(new BankBalance(null, "KBC", LocalDate.of(2026, 9, 8),
                BigDecimal.TEN, null, null, moment, "Europe/Brussels"));
        assertEquals(moment, saved.asOfAt());
        assertEquals("Europe/Brussels", saved.timeZone());
        assertEquals(LocalDate.of(2026, 9, 8), saved.date());
    }

    @Test
    void checkpointRejectsMismatchedCalendarDayAndUnknownTimeZone() {
        Instant moment = Instant.parse("2026-09-08T10:00:00Z");
        assertThrows(BusinessRuleException.class, () -> service.create(new BankBalance(null, "KBC",
                LocalDate.of(2026, 9, 7), BigDecimal.TEN, null, null, moment, "Europe/Brussels")));
        assertThrows(BusinessRuleException.class, () -> service.create(new BankBalance(null, "KBC",
                LocalDate.of(2026, 9, 8), BigDecimal.TEN, null, null, moment, "Invalid/TimeZone")));
        BankBalance legacy = service.create(new BankBalance(null, "KBC", LocalDate.of(2026, 9, 8),
                BigDecimal.TEN, null, null));
        assertNull(legacy.asOfAt());
        assertNull(legacy.timeZone());
    }
}
