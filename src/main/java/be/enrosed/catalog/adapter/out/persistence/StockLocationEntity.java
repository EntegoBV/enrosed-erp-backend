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

    public StockLocation toDomain() {
        return new StockLocation(id, code, name, kind, address, active, countsForWebsite,
                receivesByDefault, position);
    }
}
