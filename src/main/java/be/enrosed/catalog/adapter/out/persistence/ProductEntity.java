package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.PackagingKind;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "product")
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(unique = true)
    public String sku;
    public String name;

    /* Legacy storage names: length=B, width=D, height=H in all displays. */
    public BigDecimal productLengthCm;
    public BigDecimal productWidthCm;
    public BigDecimal productHeightCm;
    /** Weight of one piece, in kilograms; null when never weighed. */
    public BigDecimal productWeightKg;

    /* Gift box or display around the product, with its own outer size. */
    @Enumerated(EnumType.STRING)
    public PackagingKind packagingKind;
    public BigDecimal packagingLengthCm;
    public BigDecimal packagingWidthCm;
    public BigDecimal packagingHeightCm;
    public BigDecimal packagingWeightKg;
    /** EAN on the gift box or display itself, when it is scanned apart from the article. */
    public String packagingBarcode;
    /** Pieces one display holds; null for a gift box around a single piece. */
    public Integer packagingPiecesPerUnit;

    /* Explicit merchandising variant attributes; size is not a physical dimension. */
    public String colour;
    public String variantSize;
    @Column(length = 7)
    public String colourHex;

    /** Sales copy for the quote and the catalogue. */
    @Column(length = 2000)
    public String description;

    public Long categoryId;
    public Long supplierId;
    public boolean active = true;

    /** Canonical family relation. Nullable for pre-migration rows. */
    public Long familyId;

    /** Stable variant identity from the canonical manifest, independent from SKU. */
    @Column(unique = true)
    public String canonicalVariantKey;

    /** Product-level EAN/barcode without guessing inner- or outer-carton semantics. */
    @Column(unique = true)
    public String canonicalBarcode;

    /** Explicit merchandising order inside the family. */
    public int variantPosition;

    /** False means that zero stock is only a persistence placeholder, not an observed value. */
    public boolean inventoryKnown = true;

    /** Explicit customer-facing availability from a source such as Shopify; null is unknown. */
    public Boolean publicAvailability;

    /** Optional stable grouping key shared by related stock-bearing SKUs. */
    public String familyKey;

    /** Stable public URL identity; deliberately independent from name and SKU. */
    @Column(unique = true)
    public String publicHandle;

    @Enumerated(EnumType.STRING)
    public PublicationState websiteStatus = PublicationState.DRAFT;

    @Enumerated(EnumType.STRING)
    public PublicationState orderAppStatus = PublicationState.DRAFT;

    public String barcodeInner;
    public String barcodeOuter;
    public String hsCode;

    /* Legacy storage names: length=B, width=D, height=H in all displays. */
    public BigDecimal cartonLengthCm;
    public BigDecimal cartonWidthCm;
    public BigDecimal cartonHeightCm;
    public int piecesPerCarton = 1;
    public BigDecimal cartonWeightKg;
    /** Hand-counted pieces per 40' HC; null lets the carton size decide. */
    public Integer piecesPerHc;

    @Column(precision = 19, scale = 6)
    public BigDecimal exwPrice;
    @Enumerated(EnumType.STRING)
    public Currency exwCurrency = Currency.USD;
    @Column(precision = 19, scale = 6)
    public BigDecimal extraUnitCost;

    @Column(precision = 19, scale = 6)
    public BigDecimal landedCostEur;
    public String landedCostSource;

    @Column(precision = 19, scale = 4)
    public BigDecimal markupPct;
    @Column(precision = 19, scale = 4)
    public BigDecimal fixedSalesPriceEur;

    /** Voorraad in stuks. */
    public int stockQuantity;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    public List<ProductPhotoEntity> photos = new ArrayList<>();

    /** Naam, beschrijving en kleur in andere talen. */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("language ASC")
    public List<ProductTextEntity> texts = new ArrayList<>();
}
