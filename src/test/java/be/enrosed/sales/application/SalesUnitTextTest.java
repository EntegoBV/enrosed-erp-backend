package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One carton line names one carton noun: displays and the pieces inside read "per doos". */
class SalesUnitTextTest {

    @Test
    void aDisplayCartonCountsItsDisplaysAndThePiecesInsideWithOneCartonNoun() {
        var displays = new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 8, SalesUnit.DISPLAY, "bowl");
        assertEquals("5 displays per doos (40 bowls)", SalesUnitText.cartonContents(displays, 5, Language.NL));
        assertEquals("5 présentoirs par carton (40 bols)", SalesUnitText.cartonContents(displays, 5, Language.FR));

        var single = new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 1, SalesUnit.DISPLAY, "bowl");
        assertEquals("5 displays per doos", SalesUnitText.cartonContents(single, 5, Language.NL));

        var pieces = new Packaging(PackagingKind.DISPLAY, Dimensions.empty(), null, 8, SalesUnit.PIECE, "bowl");
        assertEquals("40 bowls per doos", SalesUnitText.cartonContents(pieces, 40, Language.NL));
        assertEquals("24 stuks per doos", SalesUnitText.cartonContents(Packaging.none(), 24, Language.NL));
    }
}
