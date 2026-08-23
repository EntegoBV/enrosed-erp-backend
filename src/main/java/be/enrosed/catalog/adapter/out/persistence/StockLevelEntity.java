package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "stock_level", uniqueConstraints = @UniqueConstraint(columnNames = {"productId", "locationId"}))
public class StockLevelEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false) public long productId;
    @Column(nullable = false) public long locationId;
    @Column(nullable = false) public int quantity;
}
