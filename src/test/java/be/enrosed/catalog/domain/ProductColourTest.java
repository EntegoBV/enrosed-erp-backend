package be.enrosed.catalog.domain;

import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The colour a document prints and whether it is exact in a language. The
 * dictionary rule of the public catalogue changes no printed word here: only
 * a standard colour without any text of its own now counts as exact.
 */
class ProductColourTest {

    @Test
    void aStandardColourWithoutItsOwnTextIsExactInEveryLanguageThroughTheDictionary() {
        Product product = product("Bordeaux", List.of());

        for (Language language : Language.values()) {
            assertEquals(language, product.colourResolved(language).sourceLanguage(), language.code());
        }
        assertEquals("Burgundy", product.colourIn(Language.EN));
        assertEquals("Bordeauxrot", product.colourIn(Language.DE));
        assertEquals("Bordeaux", product.colourIn(Language.NL));
    }

    @Test
    void aColourTextOfTheProductItselfWinsAlsoWhenBorrowed() {
        Product product = product("Bordeaux", List.of(
                new ProductText(Language.EN, null, null, "Deep wine"),
                new ProductText(Language.FR, null, null, "Lie de vin")));

        assertEquals(new LanguageFallback.Resolved<>("Deep wine", Language.EN),
                product.colourResolved(Language.EN));
        assertEquals(new LanguageFallback.Resolved<>("Lie de vin", Language.FR),
                product.colourResolved(Language.FR));
        assertEquals(new LanguageFallback.Resolved<>("Deep wine", Language.EN),
                product.colourResolved(Language.DE),
                "a document keeps borrowing the product's own text, as before the dictionary rule");
    }

    @Test
    void aStandardColourStoredInAnotherSpellingPrintsAsTypedInDutch() {
        Product product = product("  bORDEAUX ", List.of());

        /* Documents never respelled the Dutch word; only the public catalogue prints "Bordeaux". */
        assertEquals(new LanguageFallback.Resolved<>("  bORDEAUX ", Language.NL),
                product.colourResolved(Language.NL));
        assertEquals(new LanguageFallback.Resolved<>("Burgundy", Language.EN),
                product.colourResolved(Language.EN));
    }

    @Test
    void aColourOutsideTheDictionaryBorrowsOrStaysAsTypedAndIsNeverExact() {
        Product borrowed = product("Vintage roze", List.of(
                new ProductText(Language.EN, null, null, "Vintage pink")));
        Product untranslated = product("Vintage roze", List.of());

        assertEquals(new LanguageFallback.Resolved<>("Vintage pink", Language.EN),
                borrowed.colourResolved(Language.DE));
        assertEquals("Vintage roze", untranslated.colourIn(Language.DE));
        assertNull(untranslated.colourResolved(Language.DE).sourceLanguage());
        assertNull(product(null, List.of()).colourIn(Language.DE));
    }

    private static Product product(String colour, List<ProductText> texts) {
        return new Product(
                1L, "ENR-COLOUR", "Roos", Dimensions.empty(), colour, null, null, null, true,
                null, null, PublicationState.DRAFT, PublicationState.DRAFT,
                Barcodes.none(), null,
                new Carton(Dimensions.empty(), 1, BigDecimal.ONE),
                null, Currency.USD, null, null, null, null, null,
                0, List.of(), texts);
    }
}
