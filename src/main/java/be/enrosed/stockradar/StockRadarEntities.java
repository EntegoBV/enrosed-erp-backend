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

/** The last seen state per watched page and phone; one row per watch key ("coolblue-be:blauw-256"). */
public final class StockRadarEntities {

    private StockRadarEntities() {}

    @Entity
    @Table(name = "stock_radar_watch")
    public static class WatchStateEntity extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(name = "watch_key", nullable = false, length = 60, unique = true)
        public String watchKey;
        /** Deliverable or collectable within the configured window; null until the first successful read. */
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
        /** The physical stores that had it at the last read, comma-separated (Coolblue). */
        @Column(name = "places", length = 2000)
        public String places;
        /** The stores already announced in this available period (cleared by "weer weg"), so one odd read never rings a store twice. */
        @Column(name = "announced_places", length = 2000)
        public String announcedPlaces;
        @Column(name = "checked_at")
        public Instant checkedAt;
        /** The last read that gave an answer. */
        @Column(name = "good_at")
        public Instant goodAt;
        /** When `available` last flipped. */
        @Column(name = "changed_at")
        public Instant changedAt;
        @Column(name = "notified_at")
        public Instant notifiedAt;
        /** Reads in a row that said "not available" while `available` still stands. */
        @Column(name = "gone_reads")
        public Integer goneReads;
        @Column(name = "last_error", length = 500)
        public String lastError;
        @Column(name = "error_count")
        public Integer errorCount;
        /** The last failure was the shop saying no (403, 429, 503, 541, a robot check), not a broken page. */
        @Column(name = "refused")
        public Boolean refused;
        @Column(name = "check_count")
        public Long checkCount;
        @Column(name = "last_push_status")
        public Integer lastPushStatus;
        @Column(name = "last_mail_status")
        public Integer lastMailStatus;

        public static WatchStateEntity byKey(String key) {
            return WatchStateEntity.<WatchStateEntity>find("watchKey", key).firstResult();
        }
    }

    /** An extra address that gets the radar's mails too, next to enrosed.stockradar.mail-to. */
    @Entity
    @Table(name = "stock_radar_recipient")
    public static class RecipientEntity extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(name = "email", nullable = false, length = 254, unique = true)
        public String email;
        @Column(name = "created_at", nullable = false)
        public Instant createdAt;
    }
}
