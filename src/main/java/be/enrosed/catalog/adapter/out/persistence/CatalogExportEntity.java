package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One catalogue PDF made with prices or for a recipient: who made it when, for whom (the customer
 * id with the name as printed, or only the typed name of a recipient without a customer record),
 * what it held and which file it was. The ERP knows the PDF was made, not that it was sent.
 */
@Entity
@Table(name = "catalog_export",
        indexes = @Index(name = "idx_catalog_export_customer", columnList = "customer_id,exported_at"))
public class CatalogExportEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "exported_at", nullable = false) public Instant exportedAt;
    @Column(name = "exported_by", nullable = false, length = 80) public String exportedBy;
    @Column(name = "exported_by_name", length = 100) public String exportedByName;
    @Column(name = "customer_id") public Long customerId;
    @Column(name = "customer_name", length = 255) public String customerName;
    /** SIMPLE or BROCHURE, and the PDF's language code ("nl"). */
    @Column(name = "layout", nullable = false, length = 16) public String layout;
    @Column(name = "language", nullable = false, length = 4) public String language;
    @Column(name = "include_prices", nullable = false) public boolean includePrices;
    @Column(name = "prices_valid_until") public LocalDate pricesValidUntil;
    @Column(name = "product_count", nullable = false) public int productCount;
    @Column(name = "customer_price_count", nullable = false) public int customerPriceCount;
    @Column(name = "one_off_count", nullable = false) public int oneOffCount;
    @Column(name = "on_request_count", nullable = false) public int onRequestCount;
    @Column(name = "file_name", nullable = false, length = 160) public String fileName;
    @Column(name = "pdf_bytes", nullable = false) public long pdfBytes;
    @Column(name = "pdf_sha256", nullable = false, length = 64) public String pdfSha256;
}
