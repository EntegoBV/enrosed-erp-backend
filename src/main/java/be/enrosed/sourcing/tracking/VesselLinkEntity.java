package be.enrosed.sourcing.tracking;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity(name = "ContainerVesselLink")
@Table(name = "container_vessel_link")
public class VesselLinkEntity extends PanacheEntityBase {
    @Id @Column(name = "order_id") public Long orderId;
    @Column(name = "reference_hash", length = 64, nullable = false) public String referenceHash;
    @Column(name = "vessel_imo", length = 7, nullable = false) public String imo;
    @Column(name = "linked_at", nullable = false) public Instant linkedAt;
}
