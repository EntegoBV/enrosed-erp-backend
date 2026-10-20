package be.enrosed.tica;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

public final class TicaStockEntities {
    private TicaStockEntities() {}

    @Entity(name = "TicaStockLocation") @Table(name = "tica_stock_location",
            uniqueConstraints = @UniqueConstraint(name = "uk_tica_stock_source_location", columnNames = "source_location"))
    public static class Location extends PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "source_location", nullable = false, length = 160) public String sourceLocation;
        @Column(name = "stock_location_id") public Long stockLocationId;
    }

    @Entity(name = "TicaStockProduct") @Table(name = "tica_stock_product",
            uniqueConstraints = @UniqueConstraint(name = "uk_tica_stock_product", columnNames = "product_id"))
    public static class ProductUnit extends PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "product_id", nullable = false) public Long productId;
        @Column(name = "source_unit", nullable = false, length = 30) public String sourceUnit;
    }

    /** Append-only source and application snapshots. Corrections never overwrite an earlier revision. */
    @Entity(name = "TicaStockRevision") @Table(name = "tica_stock_revision",
            indexes = @Index(name = "idx_tica_stock_revision_sale", columnList = "sale_id,id"))
    public static class Revision extends PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "sale_id", nullable = false) public Long saleId;
        @Column(name = "recorded_at", nullable = false) public Instant recordedAt;
        @Column(name = "source_snapshot", nullable = false, columnDefinition = "TEXT") public String sourceSnapshot;
        @Column(name = "stock_status", nullable = false, length = 40) public String stockStatus;
        @Column(name = "stock_product_id") public Long stockProductId;
        @Column(name = "stock_location_id") public Long stockLocationId;
        @Column(name = "stock_quantity", nullable = false) public int stockQuantity;
        @Column(name = "stock_pieces") public Integer stockPieces;
        @Column(name = "stock_unit_pieces") public Integer stockUnitPieces;
        @Column(name = "source_unit", length = 30) public String sourceUnit;
    }
}
