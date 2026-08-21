package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.shared.Language;
import jakarta.persistence.*;

@Entity
@Table(name = "content_translation_text",
        uniqueConstraints = @UniqueConstraint(columnNames = {"content_translation_id", "language"}))
public class ContentTranslationTextEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_translation_id", nullable = false)
    public ContentTranslationEntity owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(4)")
    public Language language;

    @Column(name = "copy_value", nullable = false, length = 10000)
    public String value;
}
