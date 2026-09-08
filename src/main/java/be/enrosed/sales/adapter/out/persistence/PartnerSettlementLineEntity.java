package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "partner_settlement_line_snapshot", uniqueConstraints = @UniqueConstraint(columnNames = {"sales_order_id", "product_id"}))
public class PartnerSettlementLineEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "sales_order_id", nullable = false) public Long salesOrderId;
    @Column(name = "product_id", nullable = false) public Long productId;
    @Column(nullable = false) public int quantity;
    @Column(name = "proceeds_eur", nullable = false, precision = 19, scale = 2) public BigDecimal proceedsEur;
    @Column(name = "cost_eur", nullable = false, precision = 19, scale = 2) public BigDecimal costEur;
    @Column(name = "revenue_eur", nullable = false, precision = 19, scale = 2) public BigDecimal revenueEur;
    @Column(name = "advance_eur", nullable = false, precision = 19, scale = 2) public BigDecimal advanceEur;
}
