package be.enrosed.catalog.application.port.out;

import be.enrosed.shared.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogFamilyReaderTest {

    @Test
    void customerCopyFallsBackRequestedThenEnglishThenDutchThenBase() {
        CatalogFamilyReader.Family family = new CatalogFamilyReader.Family(
                1L, "family", "family", 1L, "counter", "Counter", 0, 0,
                "Base name", "Base summary", "Base description", "Base format",
                List.of("Base highlight"), null,
                List.of(
                        new CatalogFamilyReader.Text(Language.NL, "Nederlandse naam", null,
                                "Nederlandse beschrijving", null, List.of("NL highlight")),
                        new CatalogFamilyReader.Text(Language.EN, "English name", "English summary",
                                null, "English format", List.of())),
                List.of(), List.of());

        assertEquals("English name", family.nameIn(Language.FR));
        assertEquals("English summary", family.summaryIn(Language.FR));
        assertEquals("Nederlandse beschrijving", family.descriptionIn(Language.FR));
        assertEquals("English format", family.formatIn(Language.FR));
        assertEquals(List.of("NL highlight"), family.highlightsIn(Language.FR));
    }
}
