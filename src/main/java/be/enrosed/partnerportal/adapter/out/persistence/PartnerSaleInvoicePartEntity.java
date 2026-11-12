package be.enrosed.partnerportal.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only: one row per melding part that went on a sales document. A document with rows
 * here is report-backed (its pieces already left stock through the meldingen), which survives a
 * trash of the document and a later run that links the part to another document. Never updated
 * or deleted.
 */
@Entity
@Table(name = "partner_sale_invoice_part", indexes = {
        @Index(name = "idx_partner_sale_invoice_part_order", columnList = "sales_order_id"),
        @Index(name = "idx_partner_sale_invoice_part_line", columnList = "line_id")
})
public class PartnerSaleInvoicePartEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "sales_order_id")
    public Long salesOrderId;

    @Column(name = "line_id")
    public Long lineId;

    @Column(name = "quantity")
    public Integer quantity;

    @Column(name = "created_at")
    public Instant createdAt;
}
