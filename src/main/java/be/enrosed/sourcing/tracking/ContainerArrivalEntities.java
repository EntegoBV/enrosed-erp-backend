package be.enrosed.sourcing.tracking;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** Durable threshold identity and per-device acknowledgement; never purchase/finance state. */
public final class ContainerArrivalEntities {
    private ContainerArrivalEntities() {}

    @Entity(name = "ContainerArrivalNotice")
    @Table(name = "container_arrival_notice")
    public static class Notice extends PanacheEntityBase {
        @Id @Column(length = 64) public String id;
        @Column(name = "order_id", nullable = false) public long orderId;
        @Column(name = "reference_hash", nullable = false, length = 64) public String referenceHash;
        @Column(name = "threshold_days", nullable = false) public int thresholdDays;
        @Column(name = "expected_arrival", nullable = false) public LocalDate expectedArrival;
        @Column(nullable = false, length = 300) public String title;
        @Column(nullable = false, length = 1000) public String body;
        @Column(nullable = false, length = 30) public String status = "PENDING";
        @Column(name = "created_at", nullable = false) public Instant createdAt;
        @Column(name = "next_attempt_at", nullable = false) public Instant nextAttemptAt;
        @Column(name = "accepted_at") public Instant acceptedAt;
        @Column(name = "lease_token", length = 40) public String leaseToken;
    }

    @Entity(name = "ContainerArrivalDelivery")
    @Table(name = "container_arrival_delivery", uniqueConstraints = @UniqueConstraint(columnNames = {"notice_id", "subscription_id"}))
    public static class Delivery extends PanacheEntityBase {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "notice_id", nullable = false, length = 64) public String noticeId;
        @Column(name = "subscription_id", nullable = false) public long subscriptionId;
        @Column(name = "attempt_count", nullable = false) public int attemptCount;
        @Column(name = "last_status") public Integer lastStatus;
        @Column(name = "accepted_at") public Instant acceptedAt;
    }
}
