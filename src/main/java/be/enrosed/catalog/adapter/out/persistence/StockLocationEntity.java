package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.StockLocation;
import jakarta.persistence.*;

@Entity
@Table(name = "stock_location", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class StockLocationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false) public String code;
    @Column(nullable = false) public String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public StockLocation.Kind kind;
    @Column(length = 500) public String address;
    @Column(nullable = false) public boolean active = true;
    @Column(nullable = false) public boolean countsForWebsite;
    @Column(nullable = false) public boolean receivesByDefault;
    @Column(nullable = false) public int position;
    /** Nullable in JPA so schema-update can add it safely; null reads as false. */
    @Column(name = "public_pickup_point", nullable = false)
    public Boolean publicPickupPoint;
    @Column(name = "public_pickup_label")
    public String publicPickupLabel;
    @Column(name = "public_pickup_address", length = 500)
    public String publicPickupAddress;
    @Column(name = "public_pickup_instructions", length = 2000)
    public String publicPickupInstructions;
    /** Nullable in JPA so existing rows can be upgraded without a fake order. */
    @Column(name = "public_pickup_position", nullable = false)
    public Integer publicPickupPosition;

    public StockLocation toDomain() {
        return new StockLocation(id, code, name, kind, address, active, countsForWebsite,
                receivesByDefault, position, Boolean.TRUE.equals(publicPickupPoint),
                publicPickupLabel, publicPickupAddress, publicPickupInstructions,
                publicPickupPosition == null ? 0 : publicPickupPosition);
    }
}
