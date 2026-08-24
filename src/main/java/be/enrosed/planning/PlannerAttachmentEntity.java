package be.enrosed.planning;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A file pinned under an appointment: the quote, the floor plan, the photo. */
@Entity
@Table(name = "planner_attachment")
public class PlannerAttachmentEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long itemId;

    @Column(nullable = false)
    public String filename;
    @Column(nullable = false)
    public String contentType;
    public long sizeBytes;
    @Column(nullable = false)
    public String storageKey;

    @Column(nullable = false)
    public Instant addedAt = Instant.now();
}
