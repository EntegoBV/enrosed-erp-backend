package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One supplier ("versie") of a product, with that supplier's own EXW price, reference, version label and
 * agreement note. The preferred row is what the product card mirrors. Created by
 * {@code docs/migrations/2026-10-25/product-supplier-postgresql.sql}; the partial index
 * {@code uq_product_supplier_preferred} (one preferred row per product) exists on PostgreSQL only.
 * Plain text instead of enums, no relations, no foreign key.
 */
@Entity
@Table(name = "product_supplier",
       uniqueConstraints = @UniqueConstraint(name = "uk_product_supplier", columnNames = {"product_id", "supplier_id"}),
       indexes = @Index(name = "idx_product_supplier_supplier", columnList = "supplier_id"))
public class ProductSupplierEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "product_id") public long productId;
    @Column(name = "supplier_id") public long supplierId;
    @Column(name = "preferred") public boolean preferred;
    @Column(name = "active") public boolean active = true;
    @Column(name = "version_label", length = 60) public String versionLabel;
    @Column(name = "supplier_reference", length = 120) public String supplierReference;
    @Column(name = "exw_price", precision = 19, scale = 6) public BigDecimal exwPrice;
    @Column(name = "exw_currency", length = 3) public String exwCurrency;      // "USD" | "CNY" | "EUR"
    @Column(name = "extra_unit_cost", precision = 19, scale = 6) public BigDecimal extraUnitCost;
    @Column(name = "moq") public Integer moq;
    @Column(name = "lead_time_days") public Integer leadTimeDays;
    @Column(name = "supplier_note", length = 4000) public String supplierNote;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "updated_at") public Instant updatedAt;
}
