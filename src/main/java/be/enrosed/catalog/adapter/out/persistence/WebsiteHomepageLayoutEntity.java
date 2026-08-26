package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** Singleton draft/published homepage layout aggregate. */
@Entity
@Table(name = "website_homepage_layout")
public class WebsiteHomepageLayoutEntity {
    @Id
    public Long id = 1L;

    /** JPA concurrency guard; the API uses {@link #revision} for explicit conflicts. */
    @Version
    @Column(name = "row_revision", nullable = false)
    public long rowRevision;

    /** Optimistic editor revision, incremented for every effective draft or publish change. */
    @Column(nullable = false)
    public long revision;

    /** Last editor revision that was made public; draft-only saves never change it. */
    @Column(name = "published_revision", nullable = false)
    public long publishedRevision;

    @Column(name = "draft_sections_json", nullable = false, length = 4000)
    public String draftSectionsJson;

    @Column(name = "published_sections_json", nullable = false, length = 4000)
    public String publishedSectionsJson;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "published_at")
    public Instant publishedAt;
}
