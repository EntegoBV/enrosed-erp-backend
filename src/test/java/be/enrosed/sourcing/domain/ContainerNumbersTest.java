package be.enrosed.sourcing.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared case table (batches contract §4.1), the same rows as isNewerSource in purchase-phone-model.test.mts. */
class ContainerNumbersTest {

    @ParameterizedTest(name = "{0} newer than {1}: {2}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "PO-2026-048    | PO-2026-047 | true",
            "PO-2026-047    | PO-2026-047 | false",
            "PO-2026-100    | PO-2026-099 | true",
            "PO-2026-100    | PO-2026-99  | true",
            "PO-2027-001    | PO-2026-120 | true",
            "PO-2026-047    | PO-2026-048 | false",
            "po-2026-048    | PO-2026-047 | false",
            "PO-2026-048B   | PO-2026-047 | false",
            "Stolpen mei    | PO-2026-047 | false",
            "''             | PO-2026-047 | false",
            "NULL           | PO-2026-047 | false",
            "' PO-2026-048 '| PO-2026-047 | true",
            "PO-2026-048    | PO-2026-48  | false",
    })
    void theSharedCaseTable(String candidate, String than, boolean newer) {
        assertEquals(newer, ContainerNumbers.isNewer(candidate, than));
    }

    @Test
    void numbersOfAnotherShapeDoNotCompareAndEqualNumbersAreNotNewer() {
        assertTrue(ContainerNumbers.compare("PO-2026-048", "PO-2026-48").isPresent());
        assertEquals(0, ContainerNumbers.compare("PO-2026-048", "PO-2026-48").getAsInt());
        assertEquals(-1, ContainerNumbers.compare("PO-2026-047", "PO-2026-048").getAsInt());
        assertFalse(ContainerNumbers.compare("PO-2026-048B", "PO-2026-047").isPresent());
        assertFalse(ContainerNumbers.compare("PO-2026-048", null).isPresent());
        assertFalse(ContainerNumbers.isNewer("PO-2026-048", "  "));
        assertTrue(ContainerNumbers.isNewer("PO-2026-123456789012345678901", "PO-2026-123456789012345678900"),
                "digit runs longer than a long still compare");
    }
}
