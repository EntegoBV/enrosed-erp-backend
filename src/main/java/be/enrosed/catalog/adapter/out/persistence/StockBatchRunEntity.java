package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The start of batches ("Batches opbouwen"): no row = not started; a row with {@code pausedAt} = paused.
 * Created by {@code docs/migrations/2026-10-25/stock-batches-postgresql.sql}; all nullable.
 */
@Entity
@Table(name = "stock_batch_run")
public class StockBatchRunEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") public Long id;
    @Column(name = "ran_at") public Instant ranAt;
    @Column(name = "ran_by", length = 80) public String ranBy;
    @Column(name = "products") public Integer products;
    @Column(name = "batches") public Integer batches;
    @Column(name = "opening_pieces") public Integer openingPieces;
    @Column(name = "unassigned_pieces") public Integer unassignedPieces;
    @Column(name = "note", length = 500) public String note;
    @Column(name = "paused_at") public Instant pausedAt;
    @Column(name = "paused_by", length = 80) public String pausedBy;
}
