package be.enrosed.prospect;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One database lock serializes this low-volume CRM's deduplication and reservations across replicas. */
@Entity
@Table(name = "prospect_outreach_lock")
public class ProspectOutreachLockEntity {
    @Id public Long id;
}
