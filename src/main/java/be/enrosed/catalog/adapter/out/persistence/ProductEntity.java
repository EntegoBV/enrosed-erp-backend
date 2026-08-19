package be.enrosed.catalog.adapter.out.persistence;

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

    /* Dimensions of the product itself. */
    public BigDecimal productLengthCm;
    public BigDecimal productWidthCm;
    public BigDecimal productHeightCm;

    /* A key, like colour. */
    public String colour;

    /** Sales copy for the quote and the catalogue. */
    @Column(length = 2000)
    public String description;

    public Long categoryId;
    public Long supplierId;
    public boolean active = true;

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

    /* Dimensions of the outer carton. */
    public BigDecimal cartonLengthCm;
    public BigDecimal cartonWidthCm;
    public BigDecimal cartonHeightCm;
    public int piecesPerCarton = 1;
    public BigDecimal cartonWeightKg;

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
