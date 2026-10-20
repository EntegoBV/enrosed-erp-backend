package be.enrosed.finance.application;

import be.enrosed.finance.domain.CostParty;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
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
                    party.vatPct(), party.salesChannel(), party.iban(), party.country(), party.vatNumber(), party.vatRegime(),
                    party.createdAt());
        });
        when(parties.findByKey(any())).thenReturn(Optional.empty());
        when(parties.mergedInto(org.mockito.ArgumentMatchers.anyLong())).thenReturn(null);
        service = new CostPartyService(parties);
        service.currentActor = mock(CurrentActor.class);
        when(service.currentActor.name()).thenReturn("emre");
    }

    @Test
    void learningAddsAnUnknownNameOnceWithTheBookingsDefaultsAndNeverOverwrites() {
        service.learn(" TICA Trends & Trade ", "BEURS", new BigDecimal("21"), "TICA", "BE", VatRegime.BE);

        ArgumentCaptor<CostParty> learned = ArgumentCaptor.forClass(CostParty.class);
        verify(parties).save(learned.capture());
        verify(parties).findByKey("tica trends & trade");
        assertEquals("TICA Trends & Trade", learned.getValue().name());
        assertEquals("BEURS", learned.getValue().category());
        assertEquals(new BigDecimal("21"), learned.getValue().vatPct());
        assertEquals("TICA", learned.getValue().salesChannel());
        assertNull(learned.getValue().iban());
        assertEquals("BE", learned.getValue().country());
        assertEquals(VatRegime.BE, learned.getValue().vatRegime());
        assertNotNull(learned.getValue().createdAt());

        CostParty known = new CostParty(11L, "TICA Trends & Trade", "BEURS", new BigDecimal("21"), "TICA", null, "BE", null, VatRegime.BE,
                Instant.now());
        when(parties.findByKey("tica trends & trade")).thenReturn(Optional.of(known));
        service.learn("tica   trends & TRADE", "HUUR", BigDecimal.ZERO, null, "NL", VatRegime.EU_SERVICES);
        service.learn("   ", "HUUR", null, null, null, null);
        service.learn(null, "HUUR", null, null, null, null);
        service.learn("x".repeat(201), "HUUR", null, null, null, null);
        service.learn("x".repeat(99) + " ".repeat(30) + "y".repeat(100), "HUUR", null, null, null, null); // 229 characters, a key of 200
        verify(parties, times(1)).save(any(CostParty.class));

        when(parties.save(any(CostParty.class))).thenThrow(new jakarta.persistence.PersistenceException("name_key"));
        service.learn("Tegelijk BV", "HUUR", null, null, null, null);
        verify(parties, times(2)).save(any(CostParty.class));
    }

    @Test
    void learnFillsAMissingRegimeOnceAndNeverOverwrites() {
        CostParty belgian = new CostParty(11L, "Immo Ham", "HUUR", new BigDecimal("21"), null, null, "BE", null, VatRegime.BE, Instant.now());
        when(parties.findByKey("immo ham")).thenReturn(Optional.of(belgian));
        service.learn("Immo Ham", "HUUR", new BigDecimal("21"), null, "IE", VatRegime.EU_SERVICES);
        verify(parties, never()).save(any(CostParty.class));

        CostParty unknown = new CostParty(12L, "Google Ireland", "SOFTWARE", new BigDecimal("21"), null, null, null, null, null, Instant.now());
        when(parties.findByKey("google ireland")).thenReturn(Optional.of(unknown));
        service.learn("Google Ireland", "SOFTWARE", new BigDecimal("21"), null, "IE", null);
        verify(parties, never()).save(any(CostParty.class));

        service.learn("google ireland", "HUUR", BigDecimal.ZERO, "WEBSITE", "IE", VatRegime.EU_SERVICES);
        ArgumentCaptor<CostParty> filled = ArgumentCaptor.forClass(CostParty.class);
        verify(parties).save(filled.capture());
        assertEquals(12L, filled.getValue().id());
        assertEquals("Google Ireland", filled.getValue().name());
        assertEquals("SOFTWARE", filled.getValue().category(), "fill-only: the rest stays");
        assertEquals(new BigDecimal("21"), filled.getValue().vatPct());
        assertNull(filled.getValue().salesChannel());
        assertEquals("IE", filled.getValue().country());
        assertEquals(VatRegime.EU_SERVICES, filled.getValue().vatRegime());
        assertEquals(unknown.createdAt(), filled.getValue().createdAt());
    }

    @Test
    void vatNumberIsCleanedAndChecked() {
        assertEquals("BE0123456749", service.create(new CostParty(null, "Accountant", null, null, null, null, "be", "be 0123.456.749", null, null)).vatNumber());
        assertEquals("BE", service.create(new CostParty(null, "Accountant 2", null, null, null, null, "be", null, null, null)).country());
        assertEquals("NL858617262B02", service.create(new CostParty(null, "24/7 Customs", null, null, null, null, "NL", "nl858617262b02",
                VatRegime.EU_SERVICES, null)).vatNumber());
        assertEquals(VatRegime.EU_SERVICES, service.create(new CostParty(null, "Google", null, null, null, null, "IE", "IE6388047V",
                VatRegime.EU_SERVICES, null)).vatRegime());
        assertNull(service.create(new CostParty(null, "Zonder nummer", null, null, null, null, null, "  ", null, null)).vatNumber());
        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
                () -> service.create(new CostParty(null, "Fout", null, null, null, null, null, "BE2123456749", null, null)));
        assertEquals("Dit is geen geldig btw-nummer", refused.getMessage());
        assertThrows(BusinessRuleException.class, () -> service.create(new CostParty(null, "Te kort", null, null, null, null, null, "X", null, null)));
        assertEquals("De landcode bestaat uit twee letters", assertThrows(BusinessRuleException.class,
                () -> service.create(new CostParty(null, "Land", null, null, null, null, "BEL", null, null, null))).getMessage());
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

    @Test
    void mergeKeepsTargetDefaultsAndOnlyWritesTheReversibleRelationship() {
        CostParty source = new CostParty(7L, "Linc & Partners", "OVERIG", BigDecimal.ZERO, null, null, Instant.now());
        CostParty target = new CostParty(192L, "LINC & PARTNERS NV", "BOEKHOUDER", new BigDecimal("21"), null, null, Instant.now());
        when(parties.findById(7)).thenReturn(Optional.of(source));
        when(parties.findById(192)).thenReturn(Optional.of(target));

        assertEquals(target, service.merge(7, 192));
        verify(parties).lock(7, 192);
        verify(parties).setMergedInto(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(192L), any(Instant.class), org.mockito.ArgumentMatchers.eq("emre"));
        verify(parties, never()).save(any());
        verify(parties, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
        verify(parties, never()).renameEverywhere(any(), any());
    }

    @Test
    void aMergedSpellingCannotBeRecreatedOrTakenByRenamingTheCanonicalParty() {
        CostParty canonical = new CostParty(192L, "LINC & PARTNERS NV", null, null, null, null, Instant.now());
        when(parties.findById(192)).thenReturn(Optional.of(canonical));
        when(parties.findByKey("linc partners nv")).thenReturn(Optional.of(canonical));
        when(parties.isMergedKey("linc partners nv")).thenReturn(true);
        assertThrows(BusinessRuleException.class, () -> service.create(new CostParty(null, "Linc Partners NV", null, null, null, null, null)));
        assertThrows(BusinessRuleException.class, () -> service.update(192, new CostParty(null, "Linc Partners NV", null, null, null, null, null)));
        service.learn("Linc  Partners NV", null, null, null, null, null);
        verify(parties, never()).save(any());
    }

    @Test
    void aliasesProtectTheirTargetFromDeletionAndFurtherMergeChains() {
        CostParty target = new CostParty(192L, "LINC & PARTNERS NV", null, null, null, null, Instant.now());
        CostParty other = new CostParty(193L, "Ander bedrijf", null, null, null, null, Instant.now());
        when(parties.findById(192)).thenReturn(Optional.of(target));
        when(parties.findById(193)).thenReturn(Optional.of(other));
        when(parties.aliases(192)).thenReturn(List.of(new CostParties.Alias(target, 192, Instant.now(), "emre")));
        assertThrows(BusinessRuleException.class, () -> service.delete(192));
        assertThrows(BusinessRuleException.class, () -> service.merge(192, 193));
        assertThrows(BusinessRuleException.class, () -> service.merge(192, 192));
        verify(parties, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
        verify(parties, never()).setMergedInto(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }
}
