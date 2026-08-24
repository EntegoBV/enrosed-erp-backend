package be.enrosed.planning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One line in the planner: an appointment on a date, or a task to tick
 * off. Deliberately one table - the dashboard shows them side by side
 * and a task regularly grows a date ("stand opbouwen vrijdag").
 */
@Entity
@Table(name = "planner_item")
public class PlannerItemEntity extends io.quarkus.hibernate.orm.panache.PanacheEntityBase {
    public enum Kind { EVENT, TASK }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Kind kind = Kind.TASK;

    @Column(nullable = false)
    public String title;

    /** The day it happens; null for a loose task. */
    public LocalDate onDate;
    /** Optional clock time, kept as "HH:mm" - no timezone dance for a fair stand. */
    public String atTime;

    @Column(length = 2000)
    public String note;

    public boolean done;

    /** Pinned to the very top of the dashboard until unpinned. */
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(nullable = false)
    public boolean pinned;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
