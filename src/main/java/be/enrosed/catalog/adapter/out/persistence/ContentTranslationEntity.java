package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.ContentScope;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One dashboard-owned copy key with revisioned translations. */
@Entity
@Table(name = "content_translation",
        uniqueConstraints = @UniqueConstraint(columnNames = {"scope", "copy_key"}))
public class ContentTranslationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(16)")
    public ContentScope scope;

    @Column(name = "copy_key", nullable = false, length = 180)
    public String key;

    @Column(nullable = false)
    public String label;

    public boolean required;

    /** Seeded contract key; identity and metadata cannot be removed or renamed by admin CRUD. */
    public boolean system;

    @Version
    public long revision;

    @Column(nullable = false)
    public Instant updatedAt;

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("language ASC")
    public List<ContentTranslationTextEntity> texts = new ArrayList<>();
}
