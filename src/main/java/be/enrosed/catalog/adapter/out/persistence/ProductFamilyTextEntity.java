package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.shared.Language;
import jakarta.persistence.*;

@Entity
@Table(name = "product_family_text",
        uniqueConstraints = @UniqueConstraint(columnNames = {"family_id", "language"}))
public class ProductFamilyTextEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "family_id", nullable = false)
    public ProductFamilyEntity family;
    @Enumerated(EnumType.STRING) @Column(nullable = false, columnDefinition = "varchar(4)")
    public Language language;
    public String name;
    @Column(length = 2000) public String summary;
    @Column(length = 10000) public String description;
    public String format;
    @Column(length = 10000) public String highlightsJson;
    public String seoTitle;
    @Column(length = 2000) public String seoDescription;
}
