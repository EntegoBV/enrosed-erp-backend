package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/** One EAN the company owns and has not put on a product yet. */
@Entity
@Table(name = "barcode_pool", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class BarcodePoolEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 14) public String code;
    @Column(nullable = false) public Instant addedAt;
}
