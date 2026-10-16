package be.enrosed.catalog.application;

import be.enrosed.sourcing.domain.PurchaseOrderName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The history names a container by the rule sales uses, repeated because the catalogue does not depend on sourcing. */
class ProductCostHistoryContainerNameTest {

    @Test
    void theHistoryNamesAContainerTheWaySalesDoes() {
        Object[][] cases = {
                {7L, "PO-2026-041", "Kerstcontainer"},
                {7L, "PO-2026-041", "  "},
                {7L, " PO-2026-041 ", null},
                {7L, null, null},
                {null, "", ""},
        };
        for (Object[] row : cases) {
            Long id = (Long) row[0];
            String number = (String) row[1];
            String alias = (String) row[2];
            assertEquals(PurchaseOrderName.display(id, number, alias),
                    ProductCostHistoryService.containerName(id, number, alias));
        }
        assertEquals("Kerstcontainer", ProductCostHistoryService.containerName(7L, "PO-2026-041", "Kerstcontainer"));
        assertEquals("Inkoop #7", ProductCostHistoryService.containerName(7L, null, null));
    }
}
