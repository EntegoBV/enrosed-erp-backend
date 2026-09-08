package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.domain.RecurringCost;
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
@Table(name = "recurring_cost")
public class RecurringCostEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 300) public String name;
    @Column(nullable = false, length = 60) public String category;
    @Column(length = 200) public String party;
    @Column(name = "amount_excl_eur", nullable = false, precision = 19, scale = 2) public BigDecimal amountExclEur;
    @Column(name = "vat_pct", precision = 5, scale = 2) public BigDecimal vatPct;
    @Column(name = "sales_channel", length = 40) public String salesChannel;
    @Column(name = "interval_code", nullable = false, length = 20) public String interval;
    @Column(name = "start_date", nullable = false) public LocalDate startDate;
    @Column(name = "end_date") public LocalDate endDate;
    @Column(name = "next_date") public LocalDate nextDate;
    /* Wrappers: the dev database cannot add a NOT NULL column to a filled table. */
    @Column(name = "active") public Boolean active;
    @Column(name = "auto_paid") public Boolean autoPaid;
    @Column(length = 120) public String reference;
    @Column(length = 2000) public String notes;
    @Column(name = "last_booked_on") public LocalDate lastBookedOn;
    @Column(name = "created_at") public Instant createdAt;

    public RecurringCost toDomain() {
        return new RecurringCost(id, name, category, party, amountExclEur, vatPct, salesChannel,
                interval == null ? null : RecurringCost.Interval.valueOf(interval), startDate, endDate, nextDate,
                Boolean.TRUE.equals(active), Boolean.TRUE.equals(autoPaid), reference, notes, lastBookedOn, createdAt);
    }

    public void apply(RecurringCost definition) {
        name = definition.name();
        category = definition.category();
        party = definition.party();
        amountExclEur = definition.amountExclEur();
        vatPct = definition.vatPct();
        salesChannel = definition.salesChannel();
        interval = definition.interval() == null ? null : definition.interval().name();
        startDate = definition.startDate();
        endDate = definition.endDate();
        nextDate = definition.nextDate();
        active = definition.active();
        autoPaid = definition.autoPaid();
        reference = definition.reference();
        notes = definition.notes();
        lastBookedOn = definition.lastBookedOn();
        createdAt = definition.createdAt();
    }
}
