package be.enrosed.catalog.adapter.out.persistence;

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

    /* Afmeting van het product zelf. */
    public BigDecimal productLengthCm;
    public BigDecimal productWidthCm;
    public BigDecimal productHeightCm;

    /* Sleutel zoals kleur. */
    public String colour;

    /** Verkoopstekst voor op de offerte en in de catalogus. */
    @Column(length = 2000)
    public String description;

    public Long categoryId;
    public Long supplierId;
    public boolean active = true;

    public String barcodeInner;
    public String barcodeOuter;
    public String hsCode;

    /* Afmeting van de omdoos. */
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
