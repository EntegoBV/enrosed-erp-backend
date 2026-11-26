package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The VAT facts a sales document holds itself (SalesVatChoices): {@code choice} is its own route where
 * that differs from its customer's standard (SalesVatChoice), {@code treatment} and {@code ratePct}
 * the regime an invoice or credit note left concept with, which it keeps whatever changes on the
 * customer afterwards. The names are text on purpose: no enum CHECK constraint, an unknown name
 * reads as "not recorded". docs/migrations/2026-11-26/sales-order-vat-choice-postgresql.sql
 */
@Entity
@Table(name = "sales_order_vat_choice")
public class SalesOrderVatChoiceEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_order_id", foreignKey = @ForeignKey(name = "fk_sales_order_vat_choice_order"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    public SalesEntities.SalesOrderEntity order;
    @Column(length = 32) public String choice;
    @Column(length = 48) public String treatment;
    @Column(name = "rate_pct", precision = 7, scale = 3) public BigDecimal ratePct;
    @Column(name = "pinned_at") public Instant pinnedAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
