package be.enrosed.partnerportal.domain;

import be.enrosed.catalog.domain.Packaging;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.ColourSwatches;
import be.enrosed.shared.Language;
import be.enrosed.shared.UnitNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a product is named on a tile: the series and what tells the variant apart, the same split
 * as the ERP's productParts (frontend customer-price-state.ts): "Rozenstolp M" with "Rood", the
 * colour taken off the end of the stored name, a size the name already ends with not repeated.
 */
public final class PartnerProductNames {

    public record Names(String name, String variant, String colourHex, String unitOne, String unitOther,
                        int piecesPerUnit) {}

    private PartnerProductNames() {}

    public static Names of(Product product) {
        String stored = product.name() == null ? "" : product.name().strip();
        String name = stripColour(stored, product.colour());
        if (name.isEmpty()) name = product.sku() == null ? "Product #" + product.id() : product.sku();
        String size = blankToNull(product.variantSize());
        boolean named = size != null && name.endsWith(size);
        List<String> parts = new ArrayList<>();
        if (blankToNull(product.colour()) != null) parts.add(product.colour().strip());
        if (size != null && !named) parts.add(size);
        String variant = parts.isEmpty() ? null : String.join(" · ", parts);
        String hex = blankToNull(product.colourHex());
        if (hex == null) hex = ColourSwatches.defaultFor(blankToNull(product.colour()));
        Packaging packaging = product.packaging();
        boolean display = packaging != null && packaging.soldAsDisplay();
        String key = packaging == null ? UnitNames.DEFAULT : packaging.unitKey();
        String one = display ? "display" : UnitNames.one(key, Language.NL);
        String other = display ? "displays" : UnitNames.other(key, Language.NL);
        int pieces = display ? packaging.unitPieces() : 1;
        return new Names(name, variant, hex, one, other, pieces);
    }

    /** "Rozenstolp - Rood" with colour Rood reads "Rozenstolp"; a name that is only the colour stays. */
    static String stripColour(String name, String colour) {
        String tint = blankToNull(colour);
        if (tint == null) return name;
        Pattern suffix = Pattern.compile("\\s*[-–·]\\s*" + Pattern.quote(tint) + "\\s*$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        String stripped = suffix.matcher(name).replaceAll("").strip();
        return stripped.isEmpty() ? name : stripped;
    }

    /** Case-insensitive sort key. */
    public static String sortKey(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
