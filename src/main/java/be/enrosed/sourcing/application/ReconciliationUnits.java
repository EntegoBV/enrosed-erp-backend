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
 * average per piece counts every set with its pieces; it is shown only when
 * it means something (one product, or one family sold alike): different
 * products are read per product.
 */
public final class ReconciliationUnits {

    public static final String PIECE = "PIECE";
    public static final String DISPLAY = "DISPLAY";
    public static final String MIXED = "MIXED";

    static final String SET_NOTE = "Bij sets is de kostprijs per set; per stuk = per set gedeeld door het aantal stuks in de set (bijv. 12 rozen).";

    /** A container of different products has no meaningful average per stuk or per set. */
    static String perProductNote(int products) {
        return "Deze container heeft " + products + " verschillende producten: de kost per stuk of per set staat per product, "
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
        /* One average over the container only for like products: one product, or one family sold alike. */
        java.util.Set<Object> distinctProducts = new java.util.HashSet<>();
        boolean alike = true;
        Product first = null;
        String firstUnit = null;
        int firstPieces = 0;
        String firstKey = null;
        int index = 0;
        for (PurchaseReconciliation.Line line : report.lines()) {
            Product product = line.productId() == null ? null : products.get(line.productId());
            distinctProducts.add(line.productId() == null ? "line-" + index : line.productId());
            Packaging packaging = product == null ? null : product.packaging();
            boolean set = packaging != null && packaging.soldAsDisplay();
            int pieces = set ? packaging.unitPieces() : 1;
            String key = packaging == null ? UnitNames.DEFAULT : packaging.unitKey();
            String one = packaging == null ? "stuk" : UnitNames.one(key, Language.NL);
            String other = packaging == null ? "stuks" : UnitNames.other(key, Language.NL);
            if (index == 0) {
                first = product;
                firstUnit = set ? DISPLAY : PIECE;
                firstPieces = pieces;
                firstKey = key;
            } else {
                alike &= product != null && sameFamily(first, product) && firstUnit.equals(set ? DISPLAY : PIECE)
                        && firstPieces == pieces && Objects.equals(firstKey, key);
            }
            index++;
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
        boolean average = distinctProducts.size() <= 1 || alike;
        if (totals != null) totals = totals.withAverage(distinctProducts.size(), average);
        List<String> notes = new ArrayList<>(report.notes() == null ? List.of() : report.notes());
        if (anySet) notes.add(SET_NOTE);
        if (!average) notes.add(perProductNote(distinctProducts.size()));
        return new PurchaseReconciliation(report.streams(), totals, List.copyOf(lines), List.copyOf(notes),
                report.supplierInstalments());
    }

    /** Two products of one family: the same family entity, or the same family key when neither has one. */
    static boolean sameFamily(Product left, Product right) {
        if (left == null || right == null) return false;
        if (Objects.equals(left.id(), right.id()) && left.id() != null) return true;
        if (left.familyId() != null || right.familyId() != null) return Objects.equals(left.familyId(), right.familyId());
        return left.familyKey() != null && !left.familyKey().isBlank() && left.familyKey().equals(right.familyKey());
    }

    private static BigDecimal perPiece(BigDecimal amount, int pieces) {
        return amount == null || pieces == 0 ? null
                : amount.divide(BigDecimal.valueOf(pieces), Money.UNIT_SCALE, RoundingMode.HALF_UP);
    }
}
