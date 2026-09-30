package be.enrosed.sourcing.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One rule names a container everywhere in sales: alias, else number, else "Inkoop #id". */
class PurchaseOrderNameTest {

    @Test
    void theHerkenbareNaamWinsTrimmedThenTheNumberThenTheId() {
        assertEquals("container/2026/002", new PurchaseOrderName(12L, "PO-2026-011", "  container/2026/002 ").displayName());
        assertEquals("PO-2026-011", new PurchaseOrderName(12L, "PO-2026-011", "   ").displayName());
        assertEquals("PO-2026-011", new PurchaseOrderName(12L, "PO-2026-011", null).displayName());
        assertEquals("Inkoop #12", new PurchaseOrderName(12L, " ", null).displayName());
        assertEquals("Inkoop #12", PurchaseOrderName.display(12L, null, ""));
    }
}
