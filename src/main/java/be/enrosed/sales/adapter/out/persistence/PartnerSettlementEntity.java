package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** The issued advance credit and full economic result are frozen with the settlement draft. */
@Entity
@Table(name = "partner_settlement_snapshot")
public class PartnerSettlementEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @Column(name = "purchase_order_id", nullable = false) public Long purchaseOrderId;
    @Column(name = "revenue_eur", nullable = false, precision = 19, scale = 2) public BigDecimal revenueEur;
    @Column(name = "cost_eur", nullable = false, precision = 19, scale = 2) public BigDecimal costEur;
    @Column(name = "advance_eur", nullable = false, precision = 19, scale = 2) public BigDecimal advanceEur;
}
