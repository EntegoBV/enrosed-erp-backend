package be.enrosed.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One count session: one stock location and one financial year. A full count, or a correction
 * of a booked full count when corrects_count_id is set. Status is OPEN, GEBOEKT or GEANNULEERD.
 */
@Entity
@Table(name = "stock_count")
public class StockCountEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "count_year") public Integer countYear;
    @Column(name = "location_id") public Long locationId;
    @Column(name = "location_name", length = 255) public String locationName;
    @Column(name = "corrects_count_id") public Long correctsCountId;
    @Column(name = "status", length = 16) public String status;
    @Column(name = "note", length = 1000) public String note;
    /** The reference prefix of the ledger rows this session booked. */
    @Column(name = "ledger_ref", length = 80) public String ledgerRef;
    @Column(name = "started_by", length = 64) public String startedBy;
    @Column(name = "started_by_name", length = 120) public String startedByName;
    @Column(name = "started_at") public Instant startedAt;
    @Column(name = "booked_by", length = 64) public String bookedBy;
    @Column(name = "booked_by_name", length = 120) public String bookedByName;
    @Column(name = "booked_at") public Instant bookedAt;
    @Column(name = "cancelled_by", length = 64) public String cancelledBy;
    @Column(name = "cancelled_by_name", length = 120) public String cancelledByName;
    @Column(name = "cancelled_at") public Instant cancelledAt;
}
