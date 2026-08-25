package be.enrosed.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Web-push plumbing: registered devices and our own VAPID key pair. */
public final class PushEntities {

    private PushEntities() {}

    /** One row per device/browser that asked for notifications. */
    @Entity
    @Table(name = "push_subscription")
    public static class PushSubscriptionEntity
            extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(nullable = false, length = 1000, unique = true)
        public String endpoint;
        @Column(nullable = false, length = 200)
        public String p256dh;
        @Column(nullable = false, length = 100)
        public String auth;
        @Column(length = 300)
        public String userAgent;
        public Instant createdAt = Instant.now();
        /** HTTP status of the most recent delivery attempt; null before the first. */
        public Integer lastStatus;
        public Instant lastAt;
    }

    /**
     * The VAPID key pair, generated once at first use and kept in the
     * database: the repository stays free of secrets and every deploy of
     * the same database keeps the same identity, so subscriptions survive.
     */
    @Entity
    @Table(name = "push_keys")
    public static class PushKeysEntity
            extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        @Column(nullable = false, length = 200)
        public String publicKey;
        @Column(nullable = false, length = 100)
        public String privateKey;
    }
}
