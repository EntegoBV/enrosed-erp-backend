package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.shared.Language;
import jakarta.persistence.*;

/**
 * Naam, beschrijving en kleur van een product in één taal.
 *
 * Eén rij per product en taal. De unieke sleutel daarop is er niet voor de
 * netheid: zonder die sleutel maakt een tweede import van hetzelfde
 * vertaalbestand er stilletjes dubbele rijen bij, en dan is het toeval welke
 * vertaling op de offerte belandt.
 */
@Entity
@Table(name = "product_text",
       uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "language"}))
public class ProductTextEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    public ProductEntity product;

    /*
     * Geen CHECK-constraint op deze kolom: Hibernate zou er de talen in zetten
     * die vandaag bestaan, en bij een nieuwe taal weigert de database de rij.
     * De enum bewaakt de toegestane waarden al.
     */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(4)", nullable = false)
    public Language language;

    public String name;

    @Column(length = 2000)
    public String description;

    public String colour;
}
