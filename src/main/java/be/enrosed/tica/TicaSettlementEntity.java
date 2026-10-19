package be.enrosed.tica;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

/** An intermediary settlement, never a customer credit note or a second sale. */
@Entity(name = "TicaSettlement") @Table(name = "tica_settlement")
public class TicaSettlementEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, unique = true, length = 120) public String reference;
    @Column(name = "source_key", unique = true, length = 300) public String sourceKey;
    @Column(nullable = false, length = 160) public String location;
    @Column(name = "document_date", nullable = false) public LocalDate documentDate;
    @Column(name = "due_date", nullable = false) public LocalDate dueDate;
    @Column(name = "period_from", nullable = false) public LocalDate periodFrom;
    @Column(name = "period_to", nullable = false) public LocalDate periodTo;
    @Column(name = "sales_excl", nullable = false, precision = 15, scale = 2) public BigDecimal salesExcl;
    @Column(name = "sales_vat", nullable = false, precision = 15, scale = 2) public BigDecimal salesVat;
    @Column(name = "commission_excl", nullable = false, precision = 15, scale = 2) public BigDecimal commissionExcl;
    @Column(name = "commission_vat", nullable = false, precision = 15, scale = 2) public BigDecimal commissionVat;
    @Column(name = "net_receivable", nullable = false, precision = 15, scale = 2) public BigDecimal netReceivable;
    @Column(name = "document_note", length = 2000) public String documentNote;
    @Column(name = "source_document_url", length = 2000) public String sourceDocumentUrl;
    @Column(nullable = false, length = 20) public String status = "DRAFT";
    @Column(name = "commission_cost_id", unique = true) public Long commissionCostId;
    @Column(name = "commission_payment_id", unique = true) public Long commissionPaymentId;
    @Column(name = "bank_statement_line_id", unique = true) public Long bankStatementLineId;
    @Column(name = "paid_at") public Instant paidAt;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "confirmed_at") public Instant confirmedAt;
}
