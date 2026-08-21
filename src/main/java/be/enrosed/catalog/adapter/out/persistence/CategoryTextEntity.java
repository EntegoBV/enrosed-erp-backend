package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.shared.Language;
import jakarta.persistence.*;

@Entity
@Table(name = "category_text",
        uniqueConstraints = @UniqueConstraint(columnNames = {"category_id", "language"}))
public class CategoryTextEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    public CategoryEntity category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(4)")
    public Language language;

    public String name;
    @Column(length = 4000) public String description;
    public String eyebrow;
    public String mobileName;
    public String navigationName;
    public String footerName;
}
