package be.enrosed.catalog.application;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.EnumSet;

/**
 * One validation boundary for every path that writes product master data.
 *
 * Keeping this outside the HTTP-facing service prevents bulk imports from
 * quietly accepting values that the ordinary product editor would reject.
 */
@ApplicationScoped
public class ProductValidator {

    private final BarcodeValidator barcodes;

    public ProductValidator(BarcodeValidator barcodes) {
        this.barcodes = barcodes;
    }

    public void validate(Product product) {
        if (product == null) {
            throw new BusinessRuleException("Geen productgegevens meegestuurd");
        }
        if (product.name() == null || product.name().isBlank()) {
            throw new BusinessRuleException("Naam is verplicht");
        }
        bounded(product.name(), 255, "Naam");
        bounded(product.description(), 2_000, "Beschrijving");
        bounded(product.colour(), 255, "Kleur");
        bounded(product.variantSize(), 255, "Variantmaat");
        EnumSet<be.enrosed.shared.Language> languages =
                EnumSet.noneOf(be.enrosed.shared.Language.class);
        for (ProductText text : product.texts() == null
                ? java.util.List.<ProductText>of() : product.texts()) {
            if (text == null || text.language() == null || !languages.add(text.language())) {
                throw new BusinessRuleException("Elke producttaal mag exact één keer voorkomen");
            }
            bounded(text.name(), 255, "Vertaalde productnaam");
            bounded(text.description(), 2_000, "Vertaalde productbeschrijving");
            bounded(text.colour(), 255, "Vertaalde kleur");
            bounded(text.variantSize(), 255, "Vertaalde variantmaat");
        }

        Carton carton = product.carton();
        if (carton == null || carton.piecesPerCarton() < 1) {
            throw new BusinessRuleException("Stuks per karton moet minstens 1 zijn");
        }

        validateDimensions(product.dimensions(), "Productafmeting");
        if (product.packaging().isPresent()) {
            validateDimensions(product.packaging().dimensions(),
                    product.packaging().kind().dutchLabel() + " afmeting");
        }
        validateDimensions(carton.dimensions(), "Doosafmeting");
        nonNegative(carton.weightKg(), "Doosgewicht");
        nonNegative(product.exwPrice(), "EXW-prijs");
        nonNegative(product.extraUnitCost(), "Extra kost per stuk");
        nonNegative(product.landedCostEur(), "Landed cost");
        nonNegative(product.markupPct(), "Opslagpercentage");
        nonNegative(product.fixedSalesPriceEur(), "Vaste verkoopprijs");

        Barcodes codes = product.barcodes() == null ? Barcodes.none() : product.barcodes();
        checkBarcode(codes.inner(), "Binnenbarcode");
        checkBarcode(codes.outer(), "Omdoosbarcode");
        if (product.packaging().isPresent()) {
            checkBarcode(product.packaging().barcode(),
                    "Barcode " + product.packaging().kind().dutchLabel().toLowerCase());
            Integer pieces = product.packaging().piecesPerUnit();
            if (pieces != null && pieces < 1) {
                throw new BusinessRuleException("Stuks per display moet minstens 1 zijn");
            }
        }

        if (product.publicHandle() != null
                && !product.publicHandle().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new BusinessRuleException(
                    "Publieke handle mag alleen kleine letters, cijfers en koppeltekens bevatten");
        }
        /* Case does not matter to a colour; the mapper stores capitals. */
        if (product.colourHex() != null && !product.colourHex().matches("#[0-9A-Fa-f]{6}")) {
            throw new BusinessRuleException("Kleurcode moet de vorm #RRGGBB hebben, bijvoorbeeld #A91F32");
        }
    }

    private static void validateDimensions(Dimensions dimensions, String label) {
        if (dimensions == null) return;
        nonNegative(dimensions.lengthCm(), label + " breedte (B)");
        nonNegative(dimensions.widthCm(), label + " diepte (D)");
        nonNegative(dimensions.heightCm(), label + " hoogte (H)");
        nonNegative(dimensions.weightKg(), label + " gewicht");
    }

    private static void nonNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new BusinessRuleException(label + " kan niet negatief zijn");
        }
    }

    private void checkBarcode(String value, String label) {
        BarcodeValidator.Result result = barcodes.validate(value);
        if (!result.valid()) {
            throw new BusinessRuleException(label + ": " + result.message());
        }
    }

    private static void bounded(String value, int max, String label) {
        if (value != null && value.strip().length() > max) {
            throw new BusinessRuleException(label + " is langer dan " + max + " tekens");
        }
    }
}
