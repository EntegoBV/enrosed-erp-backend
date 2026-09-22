package be.enrosed.catalog.application;

import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.shared.Language;
import be.enrosed.shared.UnprocessableBusinessRuleException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * The value types of the product-photo export: what was asked, and the plan
 * of folders and files the ZIP is streamed from.
 *
 * A plan only holds metadata and storage keys, never photo bytes: the ZIP
 * writer loads one blob at a time while it streams.
 */
public final class ProductPhotoExport {

    private ProductPhotoExport() {}

    /** Name of the top folder and of the ZIP file itself, e.g. enrosed-productfotos-2026-09-22. */
    public static String baseName(LocalDate date) {
        return "enrosed-productfotos-" + date;
    }

    public static String fileName(LocalDate date) {
        return baseName(date) + ".zip";
    }

    /** Which products are exported. Demo pieces never are. */
    public enum Scope {
        /** Active, non-demo products. */
        ACTIVE,
        /** Active, non-demo products whose series is published on the website. */
        WEBSITE,
        /** Every product, inactive ones included. */
        ALL
    }

    /** Which photos of a product are exported. */
    public enum PhotoSelection {
        /** Everything the ERP product carousel shows: own photos plus series photos for this SKU. */
        ALL,
        /** Only what the public website gallery shows for this SKU. */
        WEBSITE
    }

    /** Where a file comes from; drives its role label in the read-me. */
    public enum Source {
        /** Uploaded on this product itself. */
        PRODUCT,
        /** A series photo linked to exactly this variant. */
        VARIANT,
        /** A series photo shared by every colour of the series. */
        SERIES
    }

    public record Request(Scope scope, PhotoSelection photos, Language language) {
        public Request {
            scope = scope == null ? Scope.ACTIVE : scope;
            photos = photos == null ? PhotoSelection.ALL : photos;
            language = language == null ? Language.NL : language;
        }

        /**
         * Parses the API strings with Dutch messages; missing values take the
         * screen's defaults (active products, all photos, Dutch).
         */
        public static Request parse(String scope, String photos, String language) {
            Language parsedLanguage;
            try {
                parsedLanguage = Language.requireSupported(language, Language.NL);
            } catch (IllegalArgumentException unsupported) {
                throw new UnprocessableBusinessRuleException("Onbekende taal voor het LEESMIJ-bestand: "
                        + language.strip());
            }
            return new Request(
                    parse(Scope.class, scope, "Onbekende productselectie: "),
                    parse(PhotoSelection.class, photos, "Onbekende fotoselectie: "),
                    parsedLanguage);
        }

        private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
            if (value == null || value.isBlank()) return null;
            try {
                return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new UnprocessableBusinessRuleException(message + value.strip());
            }
        }
    }

    /** The complete export: products in folder order, with counts for the preparation screen. */
    public record Plan(Request request, List<ProductEntry> products, int photoCount, long photoBytes) {
        public Plan {
            products = List.copyOf(products);
        }

        public int productCount() {
            return products.size();
        }
    }

    /**
     * One product (SKU) with the facts the read-me lists. Texts are already
     * resolved in the export language; measurements stay value objects so
     * the read-me formats them for that language.
     *
     * Prices, costs, supplier data, stock and HS codes are deliberately
     * absent: nothing in this record may reach the read-me that should not
     * leave the company.
     */
    public record ProductEntry(
            /** Null when the product has no photos: it is listed, but gets no folder. */
            String folder,
            String sku,
            String name,
            String colour,
            String size,
            String series,
            String category,
            Dimensions dimensions,
            Packaging packaging,
            Carton carton,
            String pieceEan,
            String outerItf,
            List<FileEntry> files) {
        public ProductEntry {
            files = List.copyOf(files);
        }
    }

    /** One photo file inside a product folder: always the stored original bytes. */
    public record FileEntry(
            String name,
            String storageKey,
            String contentType,
            long sizeBytes,
            Integer widthPx,
            Integer heightPx,
            String originalFilename,
            boolean lead,
            boolean website,
            Source source) {}
}
