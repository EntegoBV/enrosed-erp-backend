package be.enrosed.sourcing.tracking;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity(name = "ContainerTrackingSnapshot")
@Table(name = "container_tracking_snapshot")
public class TrackingEntity extends PanacheEntityBase {
    @Id @Column(length = 64) public String id;
    @Column(name = "tracking_number", nullable = false, length = 80) public String trackingNumber;
    @Column(name = "shipment_id") public Long shipmentId;
    @Column(name = "observation_json", columnDefinition = "text") public String observationJson;
    @Column(name = "fingerprint", length = 64) public String fingerprint;
    @Column(name = "checked_at") public Instant checkedAt;
    @Column(name = "updated_at") public Instant updatedAt;
    @Column(name = "changed_at") public Instant changedAt;
    @Column(name = "next_check_at") public Instant nextCheckAt;
    @Column(name = "last_error", length = 500) public String lastError;
    @Column(name = "terminal", nullable = false) public boolean terminal;
}
