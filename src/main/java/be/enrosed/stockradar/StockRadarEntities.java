package be.enrosed.stockradar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The last seen state per watched shop; one row per store key. */
public final class StockRadarEntities {

    private StockRadarEntities() {}

    @Entity
    @Table(name = "stock_radar_store")
    public static class StoreStateEntity extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(name = "store_key", nullable = false, length = 40, unique = true)
        public String storeKey;
        /** Deliverable within the configured window; null until the first successful read. */
        @Column(name = "available")
        public Boolean available;
        @Column(name = "in_stock")
        public Boolean inStock;
        @Column(name = "status_text", length = 300)
        public String statusText;
        @Column(name = "delivery_by")
        public LocalDate deliveryBy;
        @Column(name = "price_eur", precision = 10, scale = 2)
        public BigDecimal priceEur;
        @Column(name = "found_url", length = 600)
        public String foundUrl;
        @Column(name = "checked_at")
        public Instant checkedAt;
        /** When `available` last flipped. */
        @Column(name = "changed_at")
        public Instant changedAt;
        @Column(name = "notified_at")
        public Instant notifiedAt;
        @Column(name = "last_error", length = 500)
        public String lastError;
        @Column(name = "error_count")
        public Integer errorCount;
        @Column(name = "check_count")
        public Long checkCount;
        @Column(name = "last_push_status")
        public Integer lastPushStatus;
        @Column(name = "last_mail_status")
        public Integer lastMailStatus;

        public static StoreStateEntity byKey(String key) {
            return StoreStateEntity.<StoreStateEntity>find("storeKey", key).firstResult();
        }
    }
}
