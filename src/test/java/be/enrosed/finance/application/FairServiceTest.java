package be.enrosed.finance.application;

import be.enrosed.finance.domain.Fair;
import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FairServiceTest {

    private Fairs fairs;
    private FairService service;

    @BeforeEach
    void setUp() {
        fairs = mock(Fairs.class);
        AtomicLong ids = new AtomicLong(20);
        when(fairs.save(any(Fair.class))).thenAnswer(call -> {
            Fair fair = call.getArgument(0);
            return fair.id() != null ? fair : new Fair(ids.incrementAndGet(), fair.code(), fair.label(), fair.active(), fair.createdAt());
        });
        when(fairs.findByCode(any())).thenReturn(Optional.empty());
        service = new FairService(fairs);
    }

    @Test
    void theCodeFollowsFromTheLabelUnlessOneIsGiven() {
        assertEquals("EDE", Fair.codeOf("Ede"));
        assertEquals("TRENDS_TRADE_EDE", Fair.codeOf("Trends & Trade Ede"));
        assertEquals("ZURICH_MOBELMESSE", Fair.codeOf(" Zürich Möbelmesse! "));
        assertEquals("X", Fair.codeOf("--x--"));
        assertNull(Fair.codeOf("&&&"));
        assertNull(Fair.codeOf(null));
        assertEquals(40, Fair.codeOf("a".repeat(45)).length());

        Fair ede = service.create(" Ede ", null);
        assertEquals("EDE", ede.code());
        assertEquals("Ede", ede.label());
        assertTrue(ede.active());
        assertNotNull(ede.id());
        assertNotNull(ede.createdAt());
        assertEquals("TICA", service.create("TICA Trends & Trade", " tica ").code(), "a given code is cleaned the same way");
    }

    @Test
    void aFairCannotTakeAFixedChannelOrAnExistingCodeOrNoNameAtAll() {
        BusinessRuleException fixed = assertThrows(BusinessRuleException.class, () -> service.create("Website", null));
        assertEquals("Deze code is een vast verkoopkanaal", fixed.getMessage());
        assertThrows(BusinessRuleException.class, () -> service.create("Ede", "partner"));

        when(fairs.findByCode("EDE")).thenReturn(Optional.of(new Fair(2L, "EDE", "Ede", true, Instant.now())));
        BusinessRuleException exists = assertThrows(BusinessRuleException.class, () -> service.create("ede", null));
        assertEquals("Deze beurs bestaat al", exists.getMessage());

        assertThrows(BusinessRuleException.class, () -> service.create("  ", null));
        assertThrows(BusinessRuleException.class, () -> service.create(null, null));
        assertThrows(BusinessRuleException.class, () -> service.create("&&&", null));
        assertThrows(BusinessRuleException.class, () -> service.create("a".repeat(121), null));
        verify(fairs, never()).save(any());
    }

    @Test
    void updatingKeepsTheCodeAndDeletingNeedsACodeNoCostCarries() {
        Fair ede = new Fair(2L, "EDE", "Ede", true, Instant.parse("2026-01-01T00:00:00Z"));
        when(fairs.findById(2L)).thenReturn(Optional.of(ede));

        Fair updated = service.update(2L, " Trends & Trade Ede ", false);
        assertEquals("EDE", updated.code());
        assertEquals("Trends & Trade Ede", updated.label());
        assertFalse(updated.active());
        assertEquals(ede.createdAt(), updated.createdAt());
        assertTrue(service.update(2L, "Ede", null).active(), "a missing switch keeps the current one");
        assertThrows(BusinessRuleException.class, () -> service.update(2L, " ", true));

        when(fairs.inUse("EDE")).thenReturn(true);
        BusinessRuleException used = assertThrows(BusinessRuleException.class, () -> service.delete(2L));
        assertEquals("Deze beurs wordt nog gebruikt; zet ze uit in plaats van ze te verwijderen.", used.getMessage());
        verify(fairs, never()).deleteById(anyLong());

        when(fairs.inUse("EDE")).thenReturn(false);
        service.delete(2L);
        verify(fairs).deleteById(2L);
    }
}
