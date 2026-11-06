package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One product of a journaled catalogue PDF, in selection order, with the price it printed (null:
 * "Prijs op aanvraag", or no prices at all), the catalogue and customer price of that moment, where
 * the printed one came from and the sales unit it was per.
 */
@Entity
@Table(name = "catalog_export_line", indexes = {
        @Index(name = "idx_catalog_export_line_export", columnList = "export_id"),
        @Index(name = "idx_catalog_export_line_product", columnList = "product_id")})
public class CatalogExportLineEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "export_id", nullable = false) public Long exportId;
    @Column(name = "position", nullable = false) public int position;
    @Column(name = "product_id", nullable = false) public Long productId;
    @Column(name = "sku", length = 255) public String sku;
    /** The product name in the PDF's language. */
    @Column(name = "name", length = 255) public String name;
    @Column(name = "sales_unit", length = 16) public String salesUnit;
    @Column(name = "pieces_per_unit") public Integer piecesPerUnit;
    @Column(name = "unit_key", length = 40) public String unitKey;
    @Column(name = "catalogue_price_eur", precision = 19, scale = 2) public BigDecimal cataloguePriceEur;
    @Column(name = "customer_price_eur", precision = 19, scale = 2) public BigDecimal customerPriceEur;
    @Column(name = "printed_price_eur", precision = 19, scale = 2) public BigDecimal printedPriceEur;
    /** CATALOGUE, CUSTOMER, ONE_OFF or ON_REQUEST; null in a PDF without prices. */
    @Column(name = "price_source", length = 16) public String priceSource;
}
