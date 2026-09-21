package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.*;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Product as it goes over the wire.
 *
 * Product dimensions, colour and the outer carton are three separate things
 * here - just like in the domain.
 */
public record ProductDto(
        Long id,
        String sku,
        String name,
        DimensionsDto dimensions,
        /** Gift box or display around the product; null or kind NONE when sold bare. */
        PackagingDto packaging,
        String colour,
        String variantSize,
        String colourHex,
        String description,
        Long categoryId,
        Long supplierId,
        String supplierNote,
        Boolean active,
        Boolean demo,
        Long familyId,
        String canonicalVariantKey,
        String canonicalBarcode,
        Integer variantPosition,
        Boolean inventoryKnown,
        String familyKey,
        String publicHandle,
        PublicationState websiteStatus,
        PublicationState orderAppStatus,
        String barcodeInner,
        String barcodeOuter,
        String hsCode,
        CartonDto carton,
        BigDecimal exwPrice,
        Currency exwCurrency,
        BigDecimal extraUnitCost,
        BigDecimal landedCostEur,
        String landedCostSource,
        BigDecimal markupPct,
        BigDecimal fixedSalesPriceEur,
        Integer stockQuantity,
        List<PhotoDto> photos,
        /** Name, description, colour and merchandising size per language. */
        List<TextDto> texts,
        /* derived, outbound only */
        List<String> publicationIssues,
        String describedAs,
        BigDecimal cartonCbm,
        BigDecimal pieceCbm,
        BigDecimal computedSalesPriceEur
) {

    /** Legacy wire names; displayed as B × D × H in this unchanged value order. */
    public record DimensionsDto(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg) {
        public DimensionsDto(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {
            this(lengthCm, widthCm, heightCm, null);
        }
    }

    public record PackagingDto(PackagingKind kind, DimensionsDto dimensions, String barcode, Integer piecesPerUnit) {}

    /** Legacy wire names; displayed as B × D × H in this unchanged value order. */
    public static final class CartonDto {
        public final BigDecimal lengthCm;
        public final BigDecimal widthCm;
        public final BigDecimal heightCm;
        public final Integer piecesPerCarton;
        public final BigDecimal weightKg;
        /** Hand-counted pieces per 40' HC; null = derived. */
        public final Integer piecesPerHc;
        /** What fits a 40' HC: the hand count, or full cartons by volume. */
        public final Integer hcCapacity;
        private Integer piecesPer20Ft;
        private boolean piecesPer20FtProvided;

        /** Missing new properties in older clients must not clear existing manual capacity. */
        @JsonCreator
        public CartonDto(@JsonProperty("lengthCm") BigDecimal lengthCm,
                         @JsonProperty("widthCm") BigDecimal widthCm,
                         @JsonProperty("heightCm") BigDecimal heightCm,
                         @JsonProperty("piecesPerCarton") Integer piecesPerCarton,
                         @JsonProperty("weightKg") BigDecimal weightKg,
                         @JsonProperty("piecesPerHc") Integer piecesPerHc,
                         @JsonProperty("hcCapacity") Integer hcCapacity) {
            this.lengthCm = lengthCm;
            this.widthCm = widthCm;
            this.heightCm = heightCm;
            this.piecesPerCarton = piecesPerCarton;
            this.weightKg = weightKg;
            this.piecesPerHc = piecesPerHc;
            this.hcCapacity = hcCapacity;
        }

        public CartonDto(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                         Integer piecesPerCarton, BigDecimal weightKg, Integer piecesPerHc,
                         Integer hcCapacity, Integer piecesPer20Ft) {
            this(lengthCm, widthCm, heightCm, piecesPerCarton, weightKg, piecesPerHc, hcCapacity);
            this.piecesPer20Ft = piecesPer20Ft;
            this.piecesPer20FtProvided = true;
        }

        public BigDecimal lengthCm() { return lengthCm; }
        public BigDecimal widthCm() { return widthCm; }
        public BigDecimal heightCm() { return heightCm; }
        public Integer piecesPerCarton() { return piecesPerCarton; }
        public BigDecimal weightKg() { return weightKg; }
        public Integer piecesPerHc() { return piecesPerHc; }
        public Integer hcCapacity() { return hcCapacity; }

        @JsonProperty("piecesPer20Ft")
        public Integer piecesPer20Ft() { return piecesPer20Ft; }

        /** Explicit null clears the manual value; an omitted property preserves it. */
        @JsonSetter("piecesPer20Ft")
        public void setPiecesPer20Ft(JsonNode value) {
            if (value != null && !value.isNull()
                    && (!value.isIntegralNumber() || !value.canConvertToInt())) {
                throw new IllegalArgumentException("Stuks per 20ft GP moet een geheel getal zijn");
            }
            piecesPer20Ft = value == null || value.isNull() ? null : value.intValue();
            piecesPer20FtProvided = true;
        }

        @JsonIgnore
        public boolean piecesPer20FtProvided() { return piecesPer20FtProvided; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CartonDto box)) return false;
            return Objects.equals(lengthCm, box.lengthCm)
                    && Objects.equals(widthCm, box.widthCm)
                    && Objects.equals(heightCm, box.heightCm)
                    && Objects.equals(piecesPerCarton, box.piecesPerCarton)
                    && Objects.equals(weightKg, box.weightKg)
                    && Objects.equals(piecesPerHc, box.piecesPerHc)
                    && Objects.equals(hcCapacity, box.hcCapacity)
                    && Objects.equals(piecesPer20Ft, box.piecesPer20Ft);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lengthCm, widthCm, heightCm, piecesPerCarton,
                    weightKg, piecesPerHc, hcCapacity, piecesPer20Ft);
        }
    }

    public record TextDto(
            Language language, String name, String description, String colour, String variantSize) {
        /** Backward-compatible request/source shape. */
        public TextDto(Language language, String name, String description, String colour) {
            this(language, name, description, colour, null);
        }
    }

    public enum PhotoOrigin { PRODUCT, FAMILY }

    public record PhotoDto(Long id, String originalFilename, String contentType, long sizeBytes,
                           Integer widthPx, Integer heightPx, int position,
                           String url, String downloadUrl,
                           Long familyPhotoId, PhotoOrigin origin, boolean readOnly,
                           /** The channels this photo opens: WEBSITE, CATALOGUE. */
                           List<be.enrosed.catalog.domain.PhotoRole> leadFor,
                           String smallUrl, String mediumUrl) {}

    public static ProductDto from(Product product) {
        Dimensions size = product.dimensions() == null ? Dimensions.empty() : product.dimensions();
        Carton carton = product.carton() == null ? Carton.empty() : product.carton();
        Dimensions cartonSize = carton.dimensions() == null ? Dimensions.empty() : carton.dimensions();
        Barcodes codes = product.barcodes() == null ? Barcodes.none() : product.barcodes();

        List<PhotoDto> photos = product.photos().stream()
                .map(photo -> new PhotoDto(photo.id(), photo.originalFilename(), photo.contentType(),
                        photo.sizeBytes(), photo.widthPx(), photo.heightPx(), photo.position(),
                        "/api/products/" + product.id() + "/photos/" + photo.id(),
                        "/api/products/" + product.id() + "/photos/" + photo.id() + "/download",
                        photo.familyPhotoId(),
                        photo.inherited() ? PhotoOrigin.FAMILY : PhotoOrigin.PRODUCT,
                        photo.inherited(),
                        photo.leadFor().stream().sorted().toList(),
                        "/api/products/" + product.id() + "/photos/" + photo.id() + "/renditions/small",
                        "/api/products/" + product.id() + "/photos/" + photo.id() + "/renditions/medium"))
                .toList();

        List<TextDto> texts = product.texts().stream()
                .map(text -> new TextDto(text.language(), text.name(), text.description(),
                        text.colour(), text.variantSize()))
                .toList();

        return new ProductDto(
                product.id(), product.sku(), product.name(),
                new DimensionsDto(size.lengthCm(), size.widthCm(), size.heightCm(), size.weightKg()),
                new PackagingDto(product.packaging().kind(), new DimensionsDto(
                        product.packaging().dimensions().lengthCm(),
                        product.packaging().dimensions().widthCm(),
                        product.packaging().dimensions().heightCm(),
                        product.packaging().dimensions().weightKg()),
                        product.packaging().barcode(),
                        product.packaging().isPresent() ? product.packaging().unitPieces() : null),
                product.colour(), product.variantSize(), product.colourHex(), product.description(),
                product.categoryId(), product.supplierId(), product.supplierNote(), product.active(),
                product.demo(),
                product.familyId(), product.canonicalVariantKey(), product.canonicalBarcode(),
                product.variantPosition(),
                product.inventoryKnown(),
                product.familyKey(), product.publicHandle(),
                product.publicationState(CatalogChannel.WEBSITE),
                product.publicationState(CatalogChannel.ORDER_APP),
                codes.inner(), codes.outer(), product.hsCode(),
                new CartonDto(cartonSize.lengthCm(), cartonSize.widthCm(), cartonSize.heightCm(),
                        carton.piecesPerCarton(), carton.weightKg(), carton.piecesPerHc(),
                        carton.hcCapacity(), carton.piecesPer20Ft()),
                product.exwPrice(), product.exwCurrency(), product.extraUnitCost(),
                product.landedCostEur(), product.landedCostSource(),
                product.markupPct(), product.fixedSalesPriceEur(), product.stockQuantity(),
                photos, texts,
                product.publicationIssues(),
                product.describe(), carton.cbm(), carton.pieceCbm(), product.computedSalesPriceEur());
    }

    public Product toDomain(Long id) {
        return toDomain(id, null);
    }

    private Product toDomain(Long id, Integer preservedPiecesPer20Ft) {
        DimensionsDto size = dimensions == null
                ? new DimensionsDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO) : dimensions;
        CartonDto box = carton == null
                ? new CartonDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, BigDecimal.ZERO, null, null) : carton;

        DimensionsDto wrap = packaging == null || packaging.dimensions() == null
                ? new DimensionsDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                : packaging.dimensions();
        Packaging presentation = packaging == null || packaging.kind() == null
                ? Packaging.none()
                : new Packaging(packaging.kind(),
                        new Dimensions(wrap.lengthCm(), wrap.widthCm(), wrap.heightCm(), wrap.weightKg()),
                        packaging.barcode(), packaging.piecesPerUnit());

        return new Product(
                id, sku, name,
                new Dimensions(size.lengthCm(), size.widthCm(), size.heightCm(), size.weightKg()),
                presentation,
                colour, variantSize, colourHex, description,
                categoryId, supplierId, supplierNote, active == null || active,
                familyId, canonicalVariantKey, canonicalBarcode,
                variantPosition == null ? 0 : variantPosition,
                inventoryKnown == null || inventoryKnown,
                familyKey, publicHandle, websiteStatus, orderAppStatus,
                new Barcodes(barcodeInner, barcodeOuter), hsCode,
                new Carton(new Dimensions(box.lengthCm(), box.widthCm(), box.heightCm()),
                        box.piecesPerCarton() == null ? 1 : box.piecesPerCarton(), box.weightKg(),
                        box.piecesPerHc(), box.piecesPer20FtProvided()
                                ? box.piecesPer20Ft() : preservedPiecesPer20Ft),
                exwPrice, exwCurrency == null ? Currency.USD : exwCurrency, extraUnitCost,
                landedCostEur, landedCostSource,
                markupPct, fixedSalesPriceEur,
                stockQuantity == null ? 0 : stockQuantity,
                List.of(),
                texts == null ? List.of() : texts.stream()
                        .filter(text -> text != null && text.language() != null)
                        .map(text -> new ProductText(text.language(), text.name(),
                                text.description(), text.colour(), text.variantSize()))
                        .toList(),
                demo != null && demo);
    }

    /** Preserves fields that older full-PUT clients could not send yet. */
    public Product toDomainForUpdate(Product current) {
        Product changes = toDomain(current.id(), current.carton() == null
                ? null : current.carton().piecesPer20Ft());
        if (inventoryKnown == null) {
            changes = changes.withCanonicalIdentity(
                    changes.familyId(), changes.canonicalVariantKey(), changes.canonicalBarcode(),
                    changes.variantPosition(), current.inventoryKnown());
        }
        return changes;
    }
}
