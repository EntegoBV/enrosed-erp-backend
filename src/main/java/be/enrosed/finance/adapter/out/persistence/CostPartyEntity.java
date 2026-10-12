package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.domain.CostParty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "cost_party")
public class CostPartyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 200) public String name;
    /** The matching key of the name; one row per key. */
    @Column(name = "name_key", nullable = false, unique = true, length = 200) public String nameKey;
    @Column(length = 60) public String category;
    @Column(name = "vat_pct", precision = 5, scale = 2) public BigDecimal vatPct;
    @Column(name = "sales_channel", length = 40) public String salesChannel;
    @Column(length = 34) public String iban;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at") public Instant updatedAt;

    public CostParty toDomain() {
        return new CostParty(id, name, category, vatPct, salesChannel, iban, createdAt);
    }

    public void apply(CostParty party) {
        name = party.name();
        nameKey = CostParty.keyOf(party.name());
        category = party.category();
        vatPct = party.vatPct();
        salesChannel = party.salesChannel();
        iban = party.iban();
        createdAt = party.createdAt();
    }
}
