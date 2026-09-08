package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sales_payment", indexes = @Index(name = "sales_payment_order_received_idx", columnList = "sales_order_id,received_at"))
public class SalesPaymentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "sales_order_id", nullable = false) public long salesOrderId;
    @Column(name = "amount_eur", nullable = false, precision = 19, scale = 2) public BigDecimal amountEur;
    @Column(name = "received_at", nullable = false) public Instant receivedAt;
    @Column(name = "time_zone", nullable = false, length = 64) public String timeZone;
    @Column(length = 500) public String reference;
    @Column(name = "recorded_at", nullable = false) public Instant recordedAt;
    @Column(length = 255) public String actor;
    @Column(nullable = false) public boolean legacy;
    @Column(name = "voided_at") public Instant voidedAt;
    @Column(name = "legacy_key", unique = true, length = 80) public String legacyKey;
}
