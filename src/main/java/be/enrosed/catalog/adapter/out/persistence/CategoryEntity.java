package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "category")
public class CategoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(unique = true)
    public String code;
    public String name;
    public String description;
    /** Explicit website collection label; never inferred from name. */
    public String eyebrow;
    public int position;
    /** Optional shorter label used by the mobile website navigation. */
    public String mobileName;
    /** Stable operational Product id selected as this category's card/collection preview. */
    public Long featuredProductId;
}
