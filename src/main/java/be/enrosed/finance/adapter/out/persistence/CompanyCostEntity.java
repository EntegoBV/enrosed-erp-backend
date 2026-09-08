package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.domain.CompanyCost;
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
    @Column(name = "vat_pct", precision = 5, scale = 2) public BigDecimal vatPct;
    @Column(length = 120) public String reference;
    @Column(name = "paid_on") public LocalDate paidOn;
    @Column(name = "sales_channel", length = 40) public String salesChannel;
    @Column(length = 2000) public String notes;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "recurring_cost_id") public Long recurringCostId;

    public CompanyCost toDomain() {
        return new CompanyCost(id, date, category, description, party, amountExclEur, vatPct,
                reference, paidOn, salesChannel, notes, createdAt, recurringCostId);
    }

    public void apply(CompanyCost cost) {
        date = cost.date();
        category = cost.category();
        description = cost.description();
        party = cost.party();
        amountExclEur = cost.amountExclEur();
        vatPct = cost.vatPct();
        reference = cost.reference();
        paidOn = cost.paidOn();
        salesChannel = cost.salesChannel();
        notes = cost.notes();
        createdAt = cost.createdAt();
        recurringCostId = cost.recurringCostId();
    }
}
