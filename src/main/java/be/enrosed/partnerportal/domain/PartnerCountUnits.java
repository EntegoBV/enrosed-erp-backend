package be.enrosed.partnerportal.domain;

import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.OrderSteps;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.SalesUnit;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.UnitNames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The units a partner counts a product in (round 2, "per roos / per bowl / per stuk"), the one
 * backend rule; the frontend mirrors it in a pure module and must say the same. Stock, parts,
 * movements and settlements stay in sales units: a count unit only converts at the edge.
 *
 * <ul>
 * <li>STUK: one piece in the product's own noun (roos/rozen, bowl/bowls, ... from {@link UnitNames}
 *     via {@code packaging.unitKey()}); 1 piece;</li>
 * <li>VERPAKKING: the display, {@code packaging.unitPieces()} pieces; offered only when the product
 *     has a DISPLAY packaging with at least 2 pieces (PIECE or DISPLAY basis);</li>
 * <li>OMDOOS: the outer carton, {@code knownPiecesPerCarton x piecesPerSalesUnit} pieces (the
 *     carton content is in sales units); offered only when that content is known and above 1.</li>
 * </ul>
 *
 * piecesPerSalesUnit = {@code packaging.unitPieces()} on a DISPLAY basis, else 1. The sales units of
 * a count = count x pieces of the unit / piecesPerSalesUnit, which must be whole ("Een display telt
 * 12 rozen: meld 12, 24, 36 … rozen of tel per display."). Units are listed smallest first (ties in
 * the order STUK, VERPAKKING, OMDOOS); the default is STUK, and a stored default the product no
 * longer offers reads as STUK.
 */
public final class PartnerCountUnits {

    public static final String STUK = "STUK";
    public static final String VERPAKKING = "VERPAKKING";
    public static final String OMDOOS = "OMDOOS";
    public static final List<String> ALL = List.of(STUK, VERPAKKING, OMDOOS);

    /** One unit a product can be counted in: its code, its Dutch nouns and the pieces one holds. */
    public record CountUnit(String unit, String one, String other, int pieces) {}

    /**
     * How one product counts: its sales unit (PIECE | DISPLAY), the pieces one sales unit holds,
     * the piece noun and the offered units, smallest first.
     */
    public record Counting(String salesUnit, int piecesPerSalesUnit, String pieceOne, String pieceOther,
                           List<CountUnit> units) {

        public Optional<CountUnit> unit(String code) {
            String wanted = normalize(code);
            if (wanted == null) return Optional.empty();
            return units.stream().filter(unit -> unit.unit().equals(wanted)).findFirst();
        }

        /** The stored default when the product still offers it, else STUK. */
        public String effectiveDefault(String stored) {
            return unit(stored).map(CountUnit::unit).orElse(STUK);
        }

        /** "1 roos" / "3 rozen" in the piece noun. */
        public String pieces(long count) {
            return count + " " + (count == 1 ? pieceOne : pieceOther);
        }

        /**
         * The sales units of {@code count} of this unit; refused when they are not whole (only a
         * per-piece count of a display-basis product can be).
         */
        public int salesUnits(CountUnit unit, int count) {
            long pieces = (long) count * unit.pieces();
            if (pieces % piecesPerSalesUnit != 0) {
                long step = piecesPerSalesUnit;
                throw new BusinessRuleException("Een display telt " + pieces(step) + ": meld " + step + ", "
                        + 2 * step + ", " + 3 * step + " … " + pieceOther + " of tel per display.");
            }
            long quantity = pieces / piecesPerSalesUnit;
            if (quantity > Integer.MAX_VALUE) throw new BusinessRuleException("Dit aantal is te groot");
            return (int) quantity;
        }
    }

    private PartnerCountUnits() {}

    /** STUK, VERPAKKING or OMDOOS from a typed code (trimmed, any case); null when blank or unknown. */
    public static String normalize(String code) {
        if (code == null || code.isBlank()) return null;
        String upper = code.strip().toUpperCase(Locale.ROOT);
        return ALL.contains(upper) ? upper : null;
    }

    /** How a product counts; a missing product (deleted since) counts per "stuk" only. */
    public static Counting of(Product product) {
        if (product == null) {
            return new Counting(SalesUnit.PIECE.name(), 1, "stuk", "stuks",
                    List.of(new CountUnit(STUK, "stuk", "stuks", 1)));
        }
        return of(product.packaging(), product.carton());
    }

    public static Counting of(Packaging packaging, Carton carton) {
        String key = packaging == null ? UnitNames.DEFAULT : packaging.unitKey();
        String pieceOne = UnitNames.one(key, Language.NL);
        String pieceOther = UnitNames.other(key, Language.NL);
        boolean displayBasis = packaging != null && packaging.soldAsDisplay();
        int perSalesUnit = displayBasis ? Math.max(1, packaging.unitPieces()) : 1;
        List<CountUnit> units = new ArrayList<>();
        units.add(new CountUnit(STUK, pieceOne, pieceOther, 1));
        if (packaging != null && packaging.kind() == PackagingKind.DISPLAY && packaging.unitPieces() >= 2) {
            units.add(new CountUnit(VERPAKKING, "display", "displays", packaging.unitPieces()));
        }
        int cartonContent = OrderSteps.knownPiecesPerCarton(carton);
        if (cartonContent > 1) {
            long pieces = (long) cartonContent * perSalesUnit;
            if (pieces <= Integer.MAX_VALUE) units.add(new CountUnit(OMDOOS, "omdoos", "omdozen", (int) pieces));
        }
        units.sort(Comparator.comparingInt(CountUnit::pieces).thenComparingInt(unit -> ALL.indexOf(unit.unit())));
        return new Counting(displayBasis ? SalesUnit.DISPLAY.name() : SalesUnit.PIECE.name(), perSalesUnit,
                pieceOne, pieceOther, List.copyOf(units));
    }
}
