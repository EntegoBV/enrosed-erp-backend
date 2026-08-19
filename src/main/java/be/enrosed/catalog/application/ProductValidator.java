package be.enrosed.catalog.application;

import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;

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

        Carton carton = product.carton();
        if (carton == null || carton.piecesPerCarton() < 1) {
            throw new BusinessRuleException("Stuks per karton moet minstens 1 zijn");
        }

        validateDimensions(product.dimensions(), "Productafmeting");
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

        if (product.publicHandle() != null
                && !product.publicHandle().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new BusinessRuleException(
                    "Publieke handle mag alleen kleine letters, cijfers en koppeltekens bevatten");
        }
    }

    private static void validateDimensions(Dimensions dimensions, String label) {
        if (dimensions == null) return;
        nonNegative(dimensions.lengthCm(), label + " lengte");
        nonNegative(dimensions.widthCm(), label + " breedte");
        nonNegative(dimensions.heightCm(), label + " hoogte");
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
}
