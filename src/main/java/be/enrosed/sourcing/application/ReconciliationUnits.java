package be.enrosed.sourcing.application;

import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Language;
import be.enrosed.shared.Money;
import be.enrosed.shared.UnitNames;
import be.enrosed.sourcing.domain.PurchaseReconciliation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Says per line whether the nacalculatie counts sets or pieces, and adds the
 * per piece figures next to the per sales unit ones. Pure: the products are
 * read, nothing is written.
 *
 * A purchase line counts and prices in the product's sales unit, so for a
 * set (a display of 12 roses) every existing quantity and unit figure is per
 * set. Those keep their meaning (sales and partner settlements multiply them
 * with sales quantities); the piece view is added: the line forecast over
 * its pieces, never a rounded set figure divided again. The container
 * average per piece counts every set with its pieces; it is shown only for a
 * container of one product. Several products are read per product (an
 * average over them matches none of them), with the range of their own
 * figures where one line is wanted.
 */
public final class ReconciliationUnits {

    public static final String PIECE = "PIECE";
    public static final String DISPLAY = "DISPLAY";
    public static final String MIXED = "MIXED";

    static final String SET_NOTE = "Bij displays is de kostprijs per display; per stuk = per display gedeeld door het aantal stuks in het display (bijv. 12 rozen).";

    /** A container of different products has no meaningful average per stuk or per display. */
    static String perProductNote(int products) {
        return "Deze container heeft " + products + " verschillende producten: de kost per stuk of per display staat per product, "
                + "zonder gemiddelde over de hele container.";
    }

    private ReconciliationUnits() {}

    public static PurchaseReconciliation apply(PurchaseReconciliation report, Map<Long, Product> productsById) {
        if (report == null || report.lines() == null) return report;
        Map<Long, Product> products = productsById == null ? Map.of() : productsById;
        List<PurchaseReconciliation.Line> lines = new ArrayList<>();
        int orderedPieces = 0;
        int usablePieces = 0;
        int unitCostPieces = 0;
        boolean anySet = false;
        boolean anyPiece = false;
        Integer sharedPieces = null;
        String sharedKey = null;
        String sharedOther = null;
        boolean sizesAgree = true;
        boolean keysAgree = true;
        for (PurchaseReconciliation.Line line : report.lines()) {
            Product product = line.productId() == null ? null : products.get(line.productId());
            Packaging packaging = product == null ? null : product.packaging();
            boolean set = packaging != null && packaging.soldAsDisplay();
            int pieces = set ? packaging.unitPieces() : 1;
            String key = packaging == null ? UnitNames.DEFAULT : packaging.unitKey();
            String one = packaging == null ? "stuk" : UnitNames.one(key, Language.NL);
            String other = packaging == null ? "stuks" : UnitNames.other(key, Language.NL);
            int pieceQuantity = line.unitCostQuantity() * pieces;
            BigDecimal linePricing = Money.nz(line.forecastPricingEur());
            lines.add(line.withPieces(set ? DISPLAY : PIECE, pieces, key, one, other, pieceQuantity,
                    perPiece(line.forecastExternalEur(), pieceQuantity), perPiece(linePricing, pieceQuantity)));
            orderedPieces += line.orderedQuantity() * pieces;
            usablePieces += line.usableQuantity() * pieces;
            unitCostPieces += pieceQuantity;
            if (set) {
                if (!anySet) {
                    sharedPieces = pieces;
                    sharedKey = key;
                    sharedOther = other;
                } else {
                    sizesAgree &= Objects.equals(sharedPieces, pieces);
                    keysAgree &= Objects.equals(sharedKey, key);
                }
                anySet = true;
            } else {
                anyPiece = true;
            }
        }
        PurchaseReconciliation.Totals totals = report.totals();
        String basis = !anySet ? PIECE : !anyPiece && sizesAgree && keysAgree ? DISPLAY : MIXED;
        if (totals != null) {
            totals = totals.withPieces(basis, anySet && sizesAgree ? sharedPieces : null,
                    anySet && keysAgree ? sharedOther : null, orderedPieces, usablePieces, unitCostPieces,
                    perPiece(totals.forecastExternalEur(), unitCostPieces),
                    perPiece(totals.forecastPricingEur(), unitCostPieces));
        }
        List<Long> productIds = report.lines().stream().map(PurchaseReconciliation.Line::productId).toList();
        int productCount = productCount(productIds);
        boolean average = containerAverage(productIds);
        if (totals != null) totals = totals.withAverage(productCount, average);
        List<String> notes = new ArrayList<>(report.notes() == null ? List.of() : report.notes());
        if (anySet) notes.add(SET_NOTE);
        if (!average) notes.add(perProductNote(productCount));
        return new PurchaseReconciliation(report.streams(), totals, List.copyOf(lines), List.copyOf(notes),
                report.supplierInstalments());
    }

    /** The different products on these lines: lines of one product count once, a line without a product on its own. */
    public static int productCount(List<Long> productIds) {
        if (productIds == null) return 0;
        java.util.Set<Object> distinct = new java.util.HashSet<>();
        for (int index = 0; index < productIds.size(); index++) {
            Long id = productIds.get(index);
            distinct.add(id == null ? "line-" + index : id);
        }
        return distinct.size();
    }

    /**
     * Whether one figure per stuk or per set for the whole container means
     * anything: only when it holds one product. Several products, even
     * variants of one family sold alike, are read per product; the screens
     * and every PDF use this one rule.
     */
    public static boolean containerAverage(List<Long> productIds) {
        return productCount(productIds) <= 1;
    }

    /**
     * One product's own figure: per sales unit, and per stuk (the same for a
     * product sold per piece).
     */
    public record UnitFigure(BigDecimal perUnit, BigDecimal perPiece, boolean set) {}

    /**
     * The lowest and highest of the products' own figures: what a container
     * of different products shows where one product shows its figure. Sets
     * alone compare per set and per stuk; sets next to pieces compare per stuk.
     * Equal ends mean every product has the same figure.
     */
    public record UnitRange(boolean perSet, BigDecimal low, BigDecimal high, BigDecimal pieceLow,
                            BigDecimal pieceHigh) {}

    /** The range over these figures; null when none is known. */
    public static UnitRange range(List<UnitFigure> figures) {
        List<UnitFigure> known = figures == null ? List.of()
                : figures.stream().filter(figure -> figure != null && figure.perUnit() != null).toList();
        if (known.isEmpty()) return null;
        List<BigDecimal> pieces = known.stream()
                .map(figure -> figure.set() ? figure.perPiece() : figure.perUnit())
                .filter(Objects::nonNull).toList();
        if (known.stream().allMatch(UnitFigure::set)) {
            List<BigDecimal> sets = known.stream().map(UnitFigure::perUnit).toList();
            return new UnitRange(true, min(sets), max(sets), min(pieces), max(pieces));
        }
        if (pieces.isEmpty()) return null;
        return new UnitRange(false, min(pieces), max(pieces), min(pieces), max(pieces));
    }

    private static BigDecimal min(List<BigDecimal> values) {
        return values.stream().min(BigDecimal::compareTo).orElse(null);
    }

    private static BigDecimal max(List<BigDecimal> values) {
        return values.stream().max(BigDecimal::compareTo).orElse(null);
    }

    private static BigDecimal perPiece(BigDecimal amount, int pieces) {
        return amount == null || pieces == 0 ? null
                : amount.divide(BigDecimal.valueOf(pieces), Money.UNIT_SCALE, RoundingMode.HALF_UP);
    }
}
