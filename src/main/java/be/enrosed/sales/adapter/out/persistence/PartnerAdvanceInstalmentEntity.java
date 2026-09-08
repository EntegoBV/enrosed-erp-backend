package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "partner_advance_instalment", indexes = @Index(name = "partner_advance_instalment_purchase_idx", columnList = "purchase_order_id,position"))
public class PartnerAdvanceInstalmentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "purchase_order_id", nullable = false) public Long purchaseOrderId;
    @Column(nullable = false) public int position;
    @Column(nullable = false, length = 160) public String label;
    @Column(precision = 9, scale = 4) public BigDecimal percentage;
    @Column(name = "amount_eur", nullable = false, precision = 19, scale = 2) public BigDecimal amountEur;
    @Column(name = "due_date") public LocalDate dueDate;
    @Column(name = "sales_order_id", unique = true) public Long salesOrderId;
}
