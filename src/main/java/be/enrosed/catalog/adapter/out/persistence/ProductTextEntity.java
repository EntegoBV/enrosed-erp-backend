package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.shared.Language;
import jakarta.persistence.*;

/**
 * Name, public name, description and colour of a product in one language.
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

    /** Localized document name used on quotes and customer documents. */
    public String name;

    /** Localized public catalogue name, independently editable from the document name. */
    @Column(name = "public_name")
    public String publicName;

    @Column(length = 2000)
    public String description;

    public String colour;

    /**
     * Retired: the Maat is one language-neutral value, {@code product.variantSize}.
     * The column stays so the schema (Railway validation, H2 dev files, a rollback)
     * remains compatible; the application writes null here and never reads it.
     * Migration 2026-09-28/product-text-variant-size-neutral-postgresql.sql cleared it.
     */
    public String variantSize;
}
