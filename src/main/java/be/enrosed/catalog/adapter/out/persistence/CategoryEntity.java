package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "category")
public class CategoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** Optimistic token for the dashboard's all-language category editor. */
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    public long revision;

    @Column(unique = true)
    public String code;
    public String name;
    @Column(length = 4000)
    public String description;
    /** Explicit website collection label; never inferred from name. */
    public String eyebrow;
    public int position;
    /** Optional shorter label used by the mobile website navigation. */
    public String mobileName;
    /** Optional compact label used by the desktop website navigation. */
    public String navigationName;
    /** Optional category label used in the website footer. */
    public String footerName;
    /** Stable operational Product id selected as this category's card/collection preview. */
    public Long featuredProductId;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("language ASC")
    public List<CategoryTextEntity> texts = new ArrayList<>();
}
