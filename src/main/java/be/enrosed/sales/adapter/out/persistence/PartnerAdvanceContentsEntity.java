package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Cargo context only: never a priced sales line, reservation or stock movement. */
@Entity
@Table(name = "partner_advance_contents_snapshot")
public class PartnerAdvanceContentsEntity {
    @Id @Column(name = "sales_order_id") public Long salesOrderId;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text") public String snapshotJson;
    @Column(name = "captured_at", nullable = false) public Instant capturedAt;
}
