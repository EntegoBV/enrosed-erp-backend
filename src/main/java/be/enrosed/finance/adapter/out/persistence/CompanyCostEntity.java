package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.CostKind;
import be.enrosed.finance.domain.ExpenseType;
import be.enrosed.finance.domain.VatRegime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "company_cost")
public class CompanyCostEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "cost_date", nullable = false) public LocalDate date;
    @Column(nullable = false, length = 60) public String category;
    @Column(nullable = false, length = 300) public String description;
    @Column(length = 200) public String party;
    @Column(name = "amount_excl_eur", nullable = false, precision = 19, scale = 2) public BigDecimal amountExclEur;
    /** The total as entered incl. VAT; null for a cost entered excl. Nullable: the dev database adds it to a filled table. */
    @Column(name = "amount_incl_eur", precision = 19, scale = 2) public BigDecimal amountInclEur;
    @Column(name = "vat_pct", precision = 5, scale = 2) public BigDecimal vatPct;
    @Column(length = 120) public String reference;
    @Column(name = "paid_on") public LocalDate paidOn;
    @Column(name = "sales_channel", length = 40) public String salesChannel;
    @Column(length = 2000) public String notes;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "recurring_cost_id") public Long recurringCostId;
    /** The bank line that paid this cost; the bank service sets and clears it. */
    @Column(name = "bank_statement_line_id") public Long bankStatementLineId;
    /* The regime round: every column nullable (the dev database adds them to a filled table), enums stored as plain text. */
    @Column(name = "vat_regime", length = 24) public String vatRegime;
    @Column(name = "vat_regime_confirmed") public Boolean vatRegimeConfirmed;
    @Column(name = "supplier_country", length = 2) public String supplierCountry;
    @Column(name = "cost_kind", length = 16) public String costKind;
    @Column(name = "expense_type", length = 24) public String expenseType;
    @Column(name = "vat_deductible_pct", precision = 5, scale = 2) public BigDecimal vatDeductiblePct;
    @Column(name = "tax_deductible_pct", precision = 5, scale = 2) public BigDecimal taxDeductiblePct;
    @Column(name = "depreciation_years") public Integer depreciationYears;
    @Column(name = "depreciation_start") public LocalDate depreciationStart;
    /** When the invoice falls due; null = the cost date counts in the cash line. Only its own endpoint changes it. */
    @Column(name = "due_date") public LocalDate dueDate;

    public CompanyCost toDomain() {
        return new CompanyCost(id, date, category, description, party, amountExclEur, amountInclEur, vatPct,
                reference, paidOn, salesChannel, notes, createdAt, recurringCostId, bankStatementLineId,
                VatRegime.parse(vatRegime), vatRegimeConfirmed, supplierCountry, CostKind.parse(costKind),
                ExpenseType.parse(expenseType), vatDeductiblePct, taxDeductiblePct, depreciationYears, depreciationStart, dueDate);
    }

    public void apply(CompanyCost cost) {
        date = cost.date();
        category = cost.category();
        description = cost.description();
        party = cost.party();
        amountExclEur = cost.amountExclEur();
        amountInclEur = cost.amountInclEur();
        vatPct = cost.vatPct();
        reference = cost.reference();
        paidOn = cost.paidOn();
        salesChannel = cost.salesChannel();
        notes = cost.notes();
        createdAt = cost.createdAt();
        recurringCostId = cost.recurringCostId();
        bankStatementLineId = cost.bankStatementLineId();
        vatRegime = cost.vatRegime() == null ? null : cost.vatRegime().name();
        vatRegimeConfirmed = cost.vatRegimeConfirmed();
        supplierCountry = cost.supplierCountry();
        costKind = cost.costKind() == null ? null : cost.costKind().name();
        expenseType = cost.expenseType() == null ? null : cost.expenseType().name();
        vatDeductiblePct = cost.vatDeductiblePct();
        taxDeductiblePct = cost.taxDeductiblePct();
        depreciationYears = cost.depreciationYears();
        depreciationStart = cost.depreciationStart();
        dueDate = cost.dueDate();
    }
}
