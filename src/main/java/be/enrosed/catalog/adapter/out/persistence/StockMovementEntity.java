package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.StockMovement;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "stock_movement", indexes = @Index(columnList = "productId, at"))
public class StockMovementEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false) public long productId;
    /** Where it happened; null on lines booked before locations existed. */
    public Long locationId;
    @Column(nullable = false) public Instant at;
    @Column(nullable = false) public int delta;
    @Column(nullable = false) public int quantityAfter;
    /*
     * Stored as plain text on purpose. An enum column gets a CHECK constraint
     * listing the values known at creation, and "schema update" never widens
     * it - the first new kind would then be refused in production. Text it
     * is, and the code keeps the list.
     */
    @Column(nullable = false, length = 40) public String kind;

    public StockMovement.Kind kind() {
        return StockMovement.Kind.valueOf(kind);
    }
    public String reference;
    public String actor;
}
