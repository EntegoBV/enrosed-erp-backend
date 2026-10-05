package be.enrosed.sales.application;

import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.shared.DocumentText;
import be.enrosed.shared.Language;
import be.enrosed.shared.UnitNames;

import java.text.NumberFormat;

/**
 * The quantity phrases every customer document shares, so the quote, the
 * invoice, the packing slip and the mail count a line the same way.
 *
 * A stored sales quantity counts pieces or displays ({@link Packaging#salesUnit()});
 * a piece is called by its unit ("16 bowls"). Displays keep their own word,
 * and an old display without an explicit basis stays neutral ("sales units")
 * instead of guessing which of the two was meant.
 */
public final class SalesUnitText {

    private SalesUnitText() {}

    /** The unit key of a line whose product may be gone: then it is a plain piece. */
    public static String unitKey(Packaging packaging) {
        return packaging == null ? UnitNames.DEFAULT : packaging.unitKey();
    }

    /** Legacy frozen displays predate the explicit basis; their quantities stay neutral. */
    public static boolean unknownBasis(Packaging packaging) {
        return packaging != null && packaging.kind() == PackagingKind.DISPLAY
                && !packaging.hasExplicitSalesUnit();
    }

    public static boolean displayBasis(Packaging packaging) {
        return packaging != null && packaging.soldAsDisplay();
    }

    /** "16 bowls", "3 Displays" or, for an old display, "3 Verkoopeenheden". */
    public static String quantity(Packaging packaging, long quantity, Language language) {
        var text = DocumentText.of(language);
        if (unknownBasis(packaging)) return number(quantity, language) + " " + text.get("salesQuantityUnits");
        if (displayBasis(packaging)) return number(quantity, language) + " " + text.get("salesDisplayUnits");
        return UnitNames.count(unitKey(packaging), quantity, language);
    }

    /**
     * "40 bowls per doos"; a display basis counts its displays with the same
     * carton noun and names the pieces inside: "5 displays per doos (40 bowls)".
     */
    public static String cartonContents(Packaging packaging, int perCarton, Language language) {
        var text = DocumentText.of(language);
        int units = Math.max(1, perCarton);
        if (unknownBasis(packaging)) return units + " " + text.get("salesUnitsPerCarton");
        if (displayBasis(packaging)) {
            Integer inner = packaging.piecesPerUnit();
            return inner == null || inner < 2
                    ? text.get("salesDisplaysPerCartonCount").formatted(number(units, language))
                    : text.get("salesDisplaysPerCartonUnits").formatted(number(units, language),
                            UnitNames.count(unitKey(packaging), (long) units * inner, language));
        }
        return text.get("unitsPerCarton").formatted(UnitNames.count(unitKey(packaging), units, language));
    }

    /**
     * What comparing quantities across lines means: a total only adds up
     * between lines of the same basis and unit. Null for the neutral basis.
     */
    public static String comparisonKey(Packaging packaging) {
        if (unknownBasis(packaging)) return null;
        return displayBasis(packaging) ? "DISPLAY" : "PIECE:" + unitKey(packaging);
    }

    private static String number(long value, Language language) {
        return NumberFormat.getIntegerInstance(language.locale()).format(value);
    }
}
