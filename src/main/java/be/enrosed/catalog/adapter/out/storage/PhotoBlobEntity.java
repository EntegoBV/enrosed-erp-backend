package be.enrosed.catalog.adapter.out.storage;

import jakarta.persistence.*;

/**
 * De ruwe bytes van een foto, in de database.
 *
 * Aparte tabel en niet als kolom op de foto zelf: zo haalt een productlijst
 * nooit per ongeluk megabytes aan beeld op. Je leest een blob alleen wanneer
 * je hem echt nodig hebt.
 */
@Entity
@Table(name = "photo_blob")
public class PhotoBlobEntity {

    /** Zelfde sleutel als op de foto staat. */
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
