package be.enrosed.media;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** A folder in the library tree; parentId null is the root. Purely for people, links never depend on it. */
@Entity
@Table(name = "media_folder", indexes = @Index(name = "idx_media_folder_parent", columnList = "parent_id"))
public class MediaFolderEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 120)
    public String name;
    @Column(name = "parent_id")
    public Long parentId;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "created_by", length = 64)
    public String createdBy;
}
