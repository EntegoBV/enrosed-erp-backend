package be.enrosed.sales.application.port.out;

import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Language;

import java.util.List;

/** Outbound port turning a quote into a PDF. */
public interface QuoteDocumentRenderer {

    record Document(String filename, byte[] content, String contentType) {}

    Document render(SalesOrder order, PricedOrder priced, Customer customer, String portalUrl);

    /**
     * Renders an explicitly configured staff download. Implementations that do
     * not support presentation options retain the canonical customer document.
     */
    default Document render(SalesOrder order, PricedOrder priced, Customer customer,
                            String portalUrl, Language language, SalesPdfOptions options) {
        return render(order, priced, customer, portalUrl);
    }

    /* ---- packing slip ------------------------------------------------ */

    /**
     * One product on a pallet (or in the loose rest), enriched without prices.
     * {@code pieces} counts stored sales units; the texts say what they are
     * ("40 bowls", "5 Displays") in the slip's language.
     */
    record PackingItem(String description, int cartons, int pieces,
                       String outerCartonDimensions, Integer piecesPerOuterCarton,
                       String barcode, String outerCartonBarcode,
                       String outerCartonVolume, String outerCartonWeight,
                       String quantityText, String cartonContentsText,
                       /* The parts the v2 pakbon prints apart, in the slip's language: the product's name
                          (its family name when it has one), its colour, the product size without its
                          "B × D × H:" label, and the Maat. */
                       String name, String colour, String size, String variantSize,
                       /* The name is the colour itself, so the slip does not print it twice. Never so for a
                          family name: all colours of a family share it, and "Rose" / "Rosa" names the
                          flower as well as the colour. */
                       boolean colourInName) {
        /** Compatibility for callers written before the description was split into its parts. */
        public PackingItem(String description, int cartons, int pieces,
                           String outerCartonDimensions, Integer piecesPerOuterCarton,
                           String barcode, String outerCartonBarcode,
                           String outerCartonVolume, String outerCartonWeight,
                           String quantityText, String cartonContentsText) {
            this(description, cartons, pieces, outerCartonDimensions, piecesPerOuterCarton,
                    barcode, outerCartonBarcode, outerCartonVolume, outerCartonWeight,
                    quantityText, cartonContentsText, description, null, null, null, false);
        }

        /** Compatibility for callers that only need the operational quantities. */
        public PackingItem(String description, int cartons, int pieces) {
            this(description, cartons, pieces, null, null, null, null, null, null);
        }

        /** Compatibility for callers written before the quantities carried their unit. */
        public PackingItem(String description, int cartons, int pieces,
                           String outerCartonDimensions, Integer piecesPerOuterCarton,
                           String barcode, String outerCartonBarcode,
                           String outerCartonVolume, String outerCartonWeight) {
            this(description, cartons, pieces, outerCartonDimensions, piecesPerOuterCarton,
                    barcode, outerCartonBarcode, outerCartonVolume, outerCartonWeight, null, null);
        }

        /** Compatibility for callers written before volume and weight travelled along. */
        public PackingItem(String description, int cartons, int pieces,
                           String outerCartonDimensions, Integer piecesPerOuterCarton,
                           String barcode, String outerCartonBarcode) {
            this(description, cartons, pieces, outerCartonDimensions, piecesPerOuterCarton,
                    barcode, outerCartonBarcode, null, null);
        }
    }

    /** One pallet as it will stand on the truck. */
    record PackingPallet(String label, String type, Integer heightCm, List<PackingItem> items) {}

    /**
     * Everything the warehouse needs to pick and the transporter to load.
     * No prices - this paper travels with the goods.
     */
    record PackingSlip(SalesOrder order, Customer customer,
                       List<PackingPallet> pallets, List<PackingItem> loose,
                       int totalCartons, int totalPieces,
                       boolean looseCartons,
                       /* "48 bowls"; null when the lines count different units and a sum would lie. */
                       String totalQuantityText) {
        /** Compatibility for callers written before the quantities carried their unit. */
        public PackingSlip(SalesOrder order, Customer customer,
                           List<PackingPallet> pallets, List<PackingItem> loose,
                           int totalCartons, int totalPieces, boolean looseCartons) {
            this(order, customer, pallets, loose, totalCartons, totalPieces, looseCartons, null);
        }
    }

    Document packingSlip(PackingSlip slip);

    /** Optional warehouse-facing product master data; prices remain impossible here. */
    default Document packingSlip(PackingSlip slip, SalesPdfOptions options) {
        return packingSlip(slip);
    }
}
