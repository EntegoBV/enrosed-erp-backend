package be.enrosed.catalog.adapter.out.storage;

import jakarta.persistence.*;

/**
 * The raw bytes of a photo, in the database.
 *
 * A separate table, not a column on the photo itself: that way a product
 * list never accidentally pulls megabytes of image. You only read a blob
 * when you really need it.
 */
@Entity
@Table(name = "photo_blob")
public class PhotoBlobEntity {

    /** Same key as on the photo. */
    @Id
    @Column(length = 80)
    public String storageKey;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data", nullable = false)
    public byte[] data;

    public long sizeBytes;
    public String contentType;
    public String originalFilename;
}
