package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "partner_advance_quote_snapshot")
public class PartnerAdvanceQuoteEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @Column(name = "purchase_order_id", nullable = false) public Long purchaseOrderId;
    @Column(name = "financing_pct", nullable = false, precision = 9, scale = 4) public BigDecimal financingPct;
    @Column(name = "agreed_amount_eur", nullable = false, precision = 19, scale = 2) public BigDecimal agreedAmountEur;
    @Column(name = "share_pct", nullable = false, precision = 9, scale = 4) public BigDecimal sharePct;
}
