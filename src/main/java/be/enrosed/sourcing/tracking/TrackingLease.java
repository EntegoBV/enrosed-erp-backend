package be.enrosed.sourcing.tracking;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/** One durable account-wide lease also serialises manual refreshes with scheduled work. */
@Entity(name = "ContainerTrackingLease")
@Table(name = "container_tracking_lease")
public class TrackingLease extends PanacheEntityBase {
    @Id public Long id;
    @Column(name = "lease_token", length = 40) public String token;
    @Column(name = "lease_until") public Instant until;
}
