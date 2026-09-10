package be.enrosed.sales.adapter.out.persistence;

import be.enrosed.sales.application.PartnerInvoiceDeclarations.Mode;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

/** Explicit document wording only; never a tax treatment or a financial line. */
@Entity
@Table(name = "partner_invoice_declaration")
public class PartnerInvoiceDeclarationEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_order_id", foreignKey = @ForeignKey(name = "fk_partner_invoice_declaration_order"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    public SalesEntities.SalesOrderEntity order;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) public Mode mode;
    @Column(length = 160) public String reference;
    @Column(name = "text_version", nullable = false) public int textVersion;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
