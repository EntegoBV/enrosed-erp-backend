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
        public Boolean available;
        public Boolean inStock;
        @Column(length = 300)
        public String statusText;
        public LocalDate deliveryBy;
        @Column(precision = 10, scale = 2)
        public BigDecimal priceEur;
        @Column(length = 600)
        public String foundUrl;
        public Instant checkedAt;
        /** When `available` last flipped. */
        public Instant changedAt;
        public Instant notifiedAt;
        @Column(length = 500)
        public String lastError;
        public Integer errorCount;
        public Long checkCount;
        public Integer lastPushStatus;
        public Integer lastMailStatus;

        public static StoreStateEntity byKey(String key) {
            return StoreStateEntity.<StoreStateEntity>find("storeKey", key).firstResult();
        }
    }
}
