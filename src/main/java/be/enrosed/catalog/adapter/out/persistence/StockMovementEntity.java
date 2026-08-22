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
    @Column(nullable = false) public Instant at;
    @Column(nullable = false) public int delta;
    @Column(nullable = false) public int quantityAfter;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public StockMovement.Kind kind;
    public String reference;
    public String actor;
}
