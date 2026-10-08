package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Output: a row of the separate blocks: partner container, invoiced and not shipped, goods of
 * third parties, goods in transit, or an older invoice that was never shipped (kind OUDER).
 */
@Entity
@Table(name = "stock_closing_separate")
public class StockClosingSeparateEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "closing_id") public Long closingId;
    @Column(name = "kind", length = 16) public String kind;
    @Column(name = "purchase_order_id") public Long purchaseOrderId;
    @Column(name = "sales_order_id") public Long salesOrderId;
    @Column(name = "document_number", length = 120) public String documentNumber;
    @Column(name = "document_name", length = 255) public String documentName;
    @Column(name = "document_date") public LocalDate documentDate;
    @Column(name = "counterparty", length = 255) public String counterparty;
    @Column(name = "product_id") public Long productId;
    @Column(name = "sku", length = 120) public String sku;
    @Column(name = "product_name", length = 255) public String productName;
    @Column(name = "proposed_quantity") public Integer proposedQuantity;
    @Column(name = "quantity") public Integer quantity;
    @Column(name = "unit_value_eur", precision = 19, scale = 4) public BigDecimal unitValueEur;
    @Column(name = "value_eur", precision = 19, scale = 2) public BigDecimal valueEur;
    @Column(name = "estimated_eur", precision = 19, scale = 2) public BigDecimal estimatedEur;
    @Column(name = "included") public Boolean included;
    @Column(name = "choice", length = 24) public String choice;
    @Column(name = "ownership_date") public LocalDate ownershipDate;
    @Column(name = "shipped_on") public LocalDate shippedOn;
    @Column(name = "received_on") public LocalDate receivedOn;
    @Column(name = "paid_until_closing_eur", precision = 19, scale = 2) public BigDecimal paidUntilClosingEur;
    @Column(name = "reason", length = 1000) public String reason;
    @Column(name = "automatic") public Boolean automatic;
    @Column(name = "decision_id") public Long decisionId;
    @Column(name = "decided_by_name", length = 120) public String decidedByName;
    @Column(name = "decided_at") public Instant decidedAt;
}
