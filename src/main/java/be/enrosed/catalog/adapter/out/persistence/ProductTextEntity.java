package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.shared.Language;
import jakarta.persistence.*;

/**
 * Name, description and colour of a product in one language.
 *
 * One row per product and language. The unique key on that is not for
 * tidiness: without it a second import of the same translation file
 * silently adds duplicate rows, and then it is luck which translation
 * lands on the quote.
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
     * No CHECK constraint on this column: Hibernate would bake in the
     * languages that exist today, and a new language would get its row
     * refused by the database. The enum already guards the allowed values.
     */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(4)", nullable = false)
    public Language language;

    public String name;

    @Column(length = 2000)
    public String description;

    public String colour;
}
