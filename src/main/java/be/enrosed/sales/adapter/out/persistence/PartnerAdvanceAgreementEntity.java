package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "partner_advance_agreement")
public class PartnerAdvanceAgreementEntity {
    @Id @Column(name = "purchase_order_id") public Long purchaseOrderId;
    @Column(name = "partner_customer_id", nullable = false) public Long partnerCustomerId;
    @Column(name = "external_cost_eur", nullable = false, precision = 19, scale = 2) public BigDecimal externalCostEur;
    @Column(name = "financing_pct", nullable = false, precision = 9, scale = 4) public BigDecimal financingPct;
    @Column(name = "agreed_amount_eur", nullable = false, precision = 19, scale = 2) public BigDecimal agreedAmountEur;
    @Enumerated(EnumType.STRING) @Column(name = "financing_basis")
    public be.enrosed.sales.application.PartnerAdvanceBasis.Kind financingBasis;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
