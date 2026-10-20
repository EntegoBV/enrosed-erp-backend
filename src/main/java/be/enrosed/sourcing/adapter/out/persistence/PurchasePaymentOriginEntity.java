package be.enrosed.sourcing.adapter.out.persistence;

import be.enrosed.sourcing.domain.PurchasePaymentOrigin;
import jakarta.persistence.*;
import java.time.Instant;

/** Metadata beside the payment: ordinary payment edits cannot erase its origin. */
@Entity(name = "PurchasePaymentOriginEntity")
@Table(name = "purchase_payment_origin")
public class PurchasePaymentOriginEntity {
    @Id @Column(name = "payment_id") public Long paymentId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) public PurchasePaymentOrigin.Source source;
    @Column(name = "payer_name", length = 100) public String payerName;
    @Column(length = 500) public String note;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 100) public String updatedBy;

    public PurchasePaymentOrigin toDomain() { return new PurchasePaymentOrigin(source, payerName, note); }
}
