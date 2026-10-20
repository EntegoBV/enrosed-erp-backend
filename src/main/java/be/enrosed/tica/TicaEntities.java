package be.enrosed.tica;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

public final class TicaEntities {
    private TicaEntities() {}

    public enum NotificationMode { DAILY, SALES, SALES_AND_DAILY, NONE;
        boolean daily() { return this == DAILY || this == SALES_AND_DAILY; }
        boolean sales() { return this == SALES || this == SALES_AND_DAILY; }
    }

    @Entity(name = "TicaSettings") @Table(name = "tica_settings")
    public static class Settings extends PanacheEntityBase {
        @Id public Long id = 1L;
        @Enumerated(EnumType.STRING) @Column(name = "notification_mode", nullable = false, length = 30)
        public NotificationMode notificationMode = NotificationMode.DAILY;
        @Column(name = "summary_time", nullable = false, length = 5) public String summaryTime = "21:00";
        @Column(name = "sync_requested", nullable = false) public boolean syncRequested;
        @Column(name = "lease_token", length = 40) public String leaseToken;
        @Column(name = "lease_until") public Instant leaseUntil;
        @Column(name = "last_attempt_at") public Instant lastAttemptAt;
        @Column(name = "last_success_at") public Instant lastSuccessAt;
        @Column(name = "last_range_from") public LocalDate lastRangeFrom;
        @Column(name = "last_range_to") public LocalDate lastRangeTo;
        @Column(name = "coverage_from") public LocalDate coverageFrom;
        @Column(name = "coverage_to") public LocalDate coverageTo;
        @Column(name = "last_error", length = 500) public String lastError;
        @Column(name = "stock_enabled") public Boolean stockEnabled = false;
        @Column(name = "stock_from_date") public LocalDate stockFromDate;
    }

    @Entity(name = "TicaSale") @Table(name = "tica_sale", uniqueConstraints = @UniqueConstraint(name = "uk_tica_source_line",
            columnNames = {"exhibitor", "location", "transaction_number", "line_number"}),
            indexes = @Index(name = "idx_tica_sale_date", columnList = "sold_at"))
    public static class Sale extends PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(nullable = false, length = 160) public String location;
        @Column(nullable = false, length = 30) public String exhibitor;
        @Column(name = "sold_at", nullable = false) public LocalDateTime soldAt;
        @Column(name = "transaction_number", nullable = false, length = 80) public String transactionNumber;
        @Column(name = "line_number", nullable = false, length = 80) public String lineNumber;
        @Column(nullable = false, length = 1000) public String description;
        @Column(name = "beeyond_article_number", length = 100) public String beeyondArticleNumber;
        @Column(length = 150) public String sku;
        @Column(length = 100) public String barcode;
        @Column(nullable = false, precision = 15, scale = 3) public BigDecimal quantity;
        @Column(name = "discount_percent", nullable = false, precision = 6, scale = 2) public BigDecimal discountPercent;
        @Column(name = "gross_total", nullable = false, precision = 15, scale = 2) public BigDecimal grossTotal;
        @Column(name = "net_total", nullable = false, precision = 15, scale = 2) public BigDecimal netTotal;
        @Column(name = "vat_rate", precision = 6, scale = 2) public BigDecimal vatRate;
        @Column(name = "transaction_type", nullable = false, length = 100) public String transactionType;
        @Column(name = "product_id") public Long productId;
        /** Missing from a later complete export: retain source identity for audit/dedup, omit from totals. */
        @Column(nullable = false) public boolean active = true;
        @Column(name = "first_seen_at", nullable = false) public Instant firstSeenAt;
        @Column(name = "updated_at", nullable = false) public Instant updatedAt;
        @Column(name = "stock_status", length = 40) public String stockStatus;
        @Column(name = "stock_product_id") public Long stockProductId;
        @Column(name = "stock_location_id") public Long stockLocationId;
        /** Signed source sales quantity already reflected in stock: positive means sold. */
        @Column(name = "stock_quantity") public Integer stockQuantity;
        @Column(name = "stock_pieces") public Integer stockPieces;
        @Column(name = "stock_unit_pieces") public Integer stockUnitPieces;
        @Column(name = "stock_source_unit", length = 30) public String stockSourceUnit;
        @Column(name = "stock_updated_at") public Instant stockUpdatedAt;
        @Column(name = "stock_fingerprint", length = 64) public String stockFingerprint;
    }

    @Entity(name = "TicaNotice") @Table(name = "tica_notice", uniqueConstraints = @UniqueConstraint(name = "uk_tica_notice_key", columnNames = "notice_key"))
    public static class Notice extends PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "notice_key", nullable = false, length = 400) public String noticeKey;
        @Column(nullable = false, length = 20) public String kind;
        @Column(nullable = false, length = 120) public String title;
        @Column(nullable = false, length = 500) public String body;
        @Column(nullable = false, length = 30) public String status = "PENDING";
        @Column(name = "created_at", nullable = false) public Instant createdAt;
        @Column(name = "expires_at", nullable = false) public Instant expiresAt;
        @Column(name = "next_attempt_at", nullable = false) public Instant nextAttemptAt;
        @Column(name = "accepted_at") public Instant acceptedAt;
        @Column(name = "attempt_count", nullable = false) public int attemptCount;
        @Column(name = "lease_token", length = 40) public String leaseToken;
    }

    @Entity(name = "TicaDelivery") @Table(name = "tica_delivery", uniqueConstraints = @UniqueConstraint(name = "uk_tica_notice_device", columnNames = {"notice_id", "subscription_id"}))
    public static class Delivery extends PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "notice_id", nullable = false) public Long noticeId;
        @Column(name = "subscription_id", nullable = false) public Long subscriptionId;
        @Column(name = "accepted_at") public Instant acceptedAt;
        @Column(name = "last_status") public Integer lastStatus;
        @Column(name = "attempt_count", nullable = false) public int attemptCount;
    }
}
