package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A category's own photo: the picture the website and the catalogue open a collection with. */
@Entity
@Table(name = "category_photo")
public class CategoryPhotoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    public CategoryEntity category;

    @Column(name = "storage_key", nullable = false, length = 120)
    public String storageKey;
    @Column(name = "original_filename", nullable = false)
    public String originalFilename;
    @Column(name = "content_type", nullable = false, length = 120)
    public String contentType;
    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;
    @Column(name = "width_px")
    public Integer widthPx;
    @Column(name = "height_px")
    public Integer heightPx;
    @Column(nullable = false)
    public int position;
}
