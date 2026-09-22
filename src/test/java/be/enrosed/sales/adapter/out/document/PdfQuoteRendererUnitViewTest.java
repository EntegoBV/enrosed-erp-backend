package be.enrosed.sales.adapter.out.document;

import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The line wording of the quote and invoice PDF, without rendering a PDF.
 * The rule under test: a product packed in a display leads with the price of
 * one full display, like the website, the portal and the catalogue; the piece
 * price and the display/loose counts explain the line. Plain pieces print
 * their own price "per bowl".
 */
class PdfQuoteRendererUnitViewTest {

    private static final BigDecimal BOWL_PRICE = new BigDecimal("3.95");

    @Test
    void aPlainBowlCountsAndPricesBowls() {
        var view = view(new Packaging(PackagingKind.NONE, Dimensions.empty(), null, null,
                SalesUnit.PIECE, "bowl"), 16, BOWL_PRICE, Language.NL);

        assertEquals("bowls", view.quantityLabel());
        assertEquals("per bowl", view.priceLabel());
        assertNull(view.primaryPrice(), "the template prints the line's own unit price");
        assertEquals(List.of(), view.quantityDetails());
        assertNull(view.secondaryPrice());
        assertEquals("bowl", view(bowlPackaging(PackagingKind.NONE, null, SalesUnit.PIECE), 1,
                BOWL_PRICE, Language.NL).quantityLabel());
    }

    @Test
    void aPieceSoldInsideADisplayLeadsWithTheSetPriceAndExplainsThePiecePrice() {
        var view = view(bowlPackaging(PackagingKind.DISPLAY, 8, SalesUnit.PIECE), 19, BOWL_PRICE, Language.NL);

        assertEquals("bowls", view.quantityLabel());
        assertEquals("31,60 EUR", view.primaryPrice(), "the owner's rule: the set price comes first");
        assertEquals("per display van 8 bowls", view.priceLabel());
        assertEquals(List.of("Displays: 2", "Buiten display: 3 bowls", "8 bowls per display"),
                view.quantityDetails());
        assertEquals("3,95 EUR", view.secondaryPrice());
        assertEquals("per bowl", view.secondaryPriceLabel());

        var unpriced = view(bowlPackaging(PackagingKind.DISPLAY, 8, SalesUnit.PIECE), 16, null, Language.NL);
        assertNull(unpriced.primaryPrice());
        assertEquals("per bowl", unpriced.priceLabel(), "without a price the label names the line's own unit");
    }

    @Test
    void aDisplaySoldAsOneSetKeepsTheSetPriceFirstAndCountsTheBowlsInside() {
        var view = view(bowlPackaging(PackagingKind.DISPLAY, 8, SalesUnit.DISPLAY), 6,
                new BigDecimal("33.25"), Language.NL);

        assertEquals("Displays", view.quantityLabel());
        assertEquals("per display", view.priceLabel(), "the same word as the website and the portal");
        assertEquals("33,25 EUR", view.primaryPrice());
        assertEquals(List.of("Totaal: 48 bowls", "8 bowls per display"), view.quantityDetails());
        assertEquals("≈ 4,156 EUR", view.secondaryPrice());
        assertEquals("per bowl", view.secondaryPriceLabel());
    }

    @Test
    void theDefaultPieceReadsAsBefore() {
        var plain = view(Packaging.none(), 24, new BigDecimal("12.50"), Language.NL);
        assertEquals("stuks", plain.quantityLabel());
        assertEquals(DocumentText.of(Language.NL).get("salesPricePerPiece"), plain.priceLabel());

        var display = view(new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 12,
                SalesUnit.DISPLAY), 4, new BigDecimal("49.95"), Language.EN);
        assertEquals(List.of("Total: 48 pieces", "12 pieces per display"), display.quantityDetails());
        assertEquals("per piece", display.secondaryPriceLabel());
        assertEquals("per display", display.priceLabel());
    }

    @Test
    void frenchAndPolishUseTheirOwnFormsAndGrammar() {
        var french = view(bowlPackaging(PackagingKind.DISPLAY, 8, SalesUnit.PIECE), 1, BOWL_PRICE, Language.FR);
        assertEquals("bol", french.quantityLabel());
        assertEquals("par présentoir de 8 bols", french.priceLabel());
        assertEquals("31,60 EUR", french.primaryPrice());
        assertEquals("par bol", french.secondaryPriceLabel());
        assertEquals(List.of("Hors présentoir : 1 bol", "8 bols par présentoir"), french.quantityDetails());

        var polish = view(bowlPackaging(PackagingKind.DISPLAY, 8, SalesUnit.DISPLAY), 3,
                new BigDecimal("31.60"), Language.PL);
        assertEquals("za ekspozytor", polish.priceLabel());
        assertEquals(List.of("Łącznie: 24 miseczki", "8 miseczek w ekspozytorze"), polish.quantityDetails());
        assertEquals("za miseczkę", polish.secondaryPriceLabel());
        assertEquals("miseczki", view(bowlPackaging(PackagingKind.NONE, null, SalesUnit.PIECE), 2,
                BOWL_PRICE, Language.PL).quantityLabel());
        assertEquals("miseczek", view(bowlPackaging(PackagingKind.NONE, null, SalesUnit.PIECE), 5,
                BOWL_PRICE, Language.PL).quantityLabel());
    }

    @Test
    void anOldDisplayWithoutBasisStaysNeutralAndAMissingProductIsAPlainPiece() {
        var legacy = view(new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 8, null, "bowl"),
                3, BOWL_PRICE, Language.NL);
        assertEquals("Verkoopeenheden", legacy.quantityLabel());
        assertEquals("Prijs per eenheid", legacy.priceLabel());

        var missing = view(null, 2, BOWL_PRICE, Language.DE);
        assertEquals("Stück", missing.quantityLabel());
        assertEquals("pro Stück", missing.priceLabel());
    }

    private static Packaging bowlPackaging(PackagingKind kind, Integer pieces, SalesUnit basis) {
        return new Packaging(kind, Dimensions.empty(), null, pieces, basis, "bowl");
    }

    private static PdfQuoteRenderer.UnitView view(Packaging packaging, int quantity, BigDecimal price,
                                                  Language language) {
        return PdfQuoteRenderer.unitView(packaging, quantity, price, language, DocumentText.of(language));
    }
}
