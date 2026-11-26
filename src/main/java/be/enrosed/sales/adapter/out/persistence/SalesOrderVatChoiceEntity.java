package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

/**
 * A document's own VAT route where it differs from its customer's standard (SalesVatChoice). The name
 * is kept as text on purpose: no enum CHECK constraint, an unknown name reads as "no choice".
 * docs/migrations/2026-11-26/sales-order-vat-choice-postgresql.sql
 */
@Entity
@Table(name = "sales_order_vat_choice")
public class SalesOrderVatChoiceEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_order_id", foreignKey = @ForeignKey(name = "fk_sales_order_vat_choice_order"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    public SalesEntities.SalesOrderEntity order;
    @Column(nullable = false, length = 32) public String choice;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
