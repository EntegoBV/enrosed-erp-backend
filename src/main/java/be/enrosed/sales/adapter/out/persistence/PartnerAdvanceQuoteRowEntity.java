package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "partner_advance_quote_row_snapshot")
public class PartnerAdvanceQuoteRowEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "sales_order_id", nullable = false) public Long salesOrderId;
    @Column(name = "schedule_row_id") public Long scheduleRowId;
    @Column(nullable = false) public int position;
    @Column(nullable = false, length = 160) public String label;
    @Column(precision = 9, scale = 4) public BigDecimal percentage;
    @Column(name = "amount_eur", nullable = false, precision = 19, scale = 2) public BigDecimal amountEur;
    @Column(name = "due_date") public LocalDate dueDate;
}
