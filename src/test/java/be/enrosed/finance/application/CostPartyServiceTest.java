package be.enrosed.finance.application;

import be.enrosed.finance.domain.CostParty;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CostPartyServiceTest {

    private CostParties parties;
    private CostPartyService service;

    @BeforeEach
    void setUp() {
        parties = mock(CostParties.class);
        AtomicLong ids = new AtomicLong(10);
        when(parties.save(any(CostParty.class))).thenAnswer(call -> {
            CostParty party = call.getArgument(0);
            return party.id() != null ? party : new CostParty(ids.incrementAndGet(), party.name(), party.category(),
                    party.vatPct(), party.salesChannel(), party.iban(), party.createdAt());
        });
        when(parties.findByKey(any())).thenReturn(Optional.empty());
        service = new CostPartyService(parties);
    }

    @Test
    void learningAddsAnUnknownNameOnceWithTheBookingsDefaultsAndNeverOverwrites() {
        service.learn(" TICA Trends & Trade ", "BEURS", new BigDecimal("21"), "TICA");

        ArgumentCaptor<CostParty> learned = ArgumentCaptor.forClass(CostParty.class);
        verify(parties).save(learned.capture());
        verify(parties).findByKey("tica trends & trade");
        assertEquals("TICA Trends & Trade", learned.getValue().name());
        assertEquals("BEURS", learned.getValue().category());
        assertEquals(new BigDecimal("21"), learned.getValue().vatPct());
        assertEquals("TICA", learned.getValue().salesChannel());
        assertNull(learned.getValue().iban());
        assertNotNull(learned.getValue().createdAt());

        CostParty known = new CostParty(11L, "TICA Trends & Trade", "BEURS", new BigDecimal("21"), "TICA", null, Instant.now());
        when(parties.findByKey("tica trends & trade")).thenReturn(Optional.of(known));
        service.learn("tica   trends & TRADE", "HUUR", BigDecimal.ZERO, null);
        service.learn("   ", "HUUR", null, null);
        service.learn(null, "HUUR", null, null);
        service.learn("x".repeat(201), "HUUR", null, null);
        service.learn("x".repeat(99) + " ".repeat(30) + "y".repeat(100), "HUUR", null, null); // 229 characters, a key of 200
        verify(parties, times(1)).save(any(CostParty.class));

        when(parties.save(any(CostParty.class))).thenThrow(new jakarta.persistence.PersistenceException("name_key"));
        service.learn("Tegelijk BV", "HUUR", null, null);
        verify(parties, times(2)).save(any(CostParty.class));
    }

    @Test
    void renamingCascadesThroughTheBookingsOnlyWhenTheNameChanged() {
        ActivityLogService log = mock(ActivityLogService.class);
        @SuppressWarnings("unchecked") Instance<ActivityLogService> activity = mock(Instance.class);
        when(activity.isResolvable()).thenReturn(true);
        when(activity.get()).thenReturn(log);
        service.activity = activity;
        CostParty current = new CostParty(5L, "Tica", "BEURS", new BigDecimal("21"), "TICA", null,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(parties.findById(5L)).thenReturn(Optional.of(current));
        when(parties.renameEverywhere("Tica", "TICA Trends & Trade")).thenReturn(new CostParties.Renamed(2, 1, 0));

        CostParty renamed = service.update(5L, new CostParty(null, " TICA Trends & Trade ", "beurs", new BigDecimal("21"),
                "tica", null, null));

        assertEquals(5L, renamed.id());
        assertEquals("TICA Trends & Trade", renamed.name());
        assertEquals("BEURS", renamed.category());
        assertEquals("TICA", renamed.salesChannel());
        assertEquals(current.createdAt(), renamed.createdAt());
        verify(parties).renameEverywhere("Tica", "TICA Trends & Trade");
        verify(log).record(ActivityLogService.ACTION_UPDATED, CostPartyService.ACTIVITY_ENTITY, "5", "TICA Trends & Trade",
                "Aan wie hernoemd: Tica → TICA Trends & Trade · 2 kosten · 1 vaste kosten · 0 bankbewegingen");

        service.update(5L, new CostParty(null, "Tica", "HUUR", null, null, null, null));
        verify(parties, times(1)).renameEverywhere(any(), any());

        service.delete(5L);
        verify(parties).deleteById(5L);
        verify(parties, times(1)).renameEverywhere(any(), any());
    }

    @Test
    void aNameAlreadyInTheListIsRefusedWhateverItsCaseOrSpacing() {
        CostParty accountant = new CostParty(7L, "Accountant BV", null, null, null, null, Instant.now());
        when(parties.findByKey("accountant bv")).thenReturn(Optional.of(accountant));
        when(parties.findById(7L)).thenReturn(Optional.of(accountant));
        CostParty immo = new CostParty(8L, "Immo Ham", null, null, null, null, Instant.now());
        when(parties.findById(8L)).thenReturn(Optional.of(immo));
        when(parties.renameEverywhere(any(), any())).thenReturn(CostParties.Renamed.NONE);

        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
                () -> service.create(new CostParty(null, "  accountant   BV ", null, null, null, null, null)));
        assertEquals("Deze naam staat al in de lijst Aan wie", refused.getMessage());
        assertThrows(BusinessRuleException.class,
                () -> service.update(8L, new CostParty(null, "Accountant BV", null, null, null, null, null)));
        assertEquals("ACCOUNTANT BV", service.update(7L, new CostParty(null, "ACCOUNTANT BV", null, null, null, null, null)).name(),
                "a change of case on the same row is a rename, not a duplicate");
        verify(parties).renameEverywhere("Accountant BV", "ACCOUNTANT BV");

        assertThrows(BusinessRuleException.class, () -> service.create(new CostParty(null, " ", null, null, null, null, null)));
        assertThrows(BusinessRuleException.class,
                () -> service.create(new CostParty(null, "Te duur", null, new BigDecimal("120"), null, null, null)));
        verify(parties, never()).save(argThatIsNew());
    }

    @Test
    void theIbanIsStoredWithoutSpacesInUpperCaseAndOnlyWhenItLooksLikeOne() {
        CostParty saved = service.create(new CostParty(null, "Immo Ham", "huur", null, "direct", "be68 5390 0754 7034", null));

        assertEquals("BE68539007547034", saved.iban());
        assertEquals("HUUR", saved.category());
        assertEquals("DIRECT", saved.salesChannel());
        assertNotNull(saved.id());
        assertNull(service.create(new CostParty(null, "Zonder rekening", null, null, null, "  ", null)).iban());
        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
                () -> service.create(new CostParty(null, "Fout", null, null, null, "1234 5678", null)));
        assertEquals("Dit is geen geldig IBAN", refused.getMessage());
        assertThrows(BusinessRuleException.class,
                () -> service.create(new CostParty(null, "Te kort", null, null, null, "BE68 5390", null)));
    }

    private static CostParty argThatIsNew() {
        return org.mockito.ArgumentMatchers.argThat(party -> party != null && party.id() == null);
    }
}
