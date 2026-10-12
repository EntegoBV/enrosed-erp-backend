package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.domain.Fair;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "fair")
public class FairEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true, length = 40) public String code;
    @Column(nullable = false, length = 120) public String label;
    @Column(nullable = false) public boolean active;
    @Column(name = "created_at", nullable = false) public Instant createdAt;

    public Fair toDomain() {
        return new Fair(id, code, label, active, createdAt);
    }

    public void apply(Fair fair) {
        code = fair.code();
        label = fair.label();
        active = fair.active();
        createdAt = fair.createdAt();
    }
}
