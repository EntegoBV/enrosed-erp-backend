package be.enrosed.catalog.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductCostEntryTest {

    @Test
    void anEntryKnowsHowMuchDearerOrCheaperItsContainerWas() {
        ProductCostEntry dearer = new ProductCostEntry(1L, 9L, Instant.now(), new BigDecimal("19.2863"),
                new BigDecimal("17.5330"), "PO-2026-008", 13L, 40, new BigDecimal("19.20"), "USD", "emre");
        assertEquals(new BigDecimal("1.7533"), dearer.deltaEur());
        assertEquals(new BigDecimal("10.0"), dearer.deltaPct());

        ProductCostEntry first = new ProductCostEntry(2L, 9L, Instant.now(), new BigDecimal("17.5330"),
                null, "PO-2026-001", 1L, 40, null, null, "system");
        assertNull(first.deltaEur());
        assertNull(first.deltaPct());
    }
}
