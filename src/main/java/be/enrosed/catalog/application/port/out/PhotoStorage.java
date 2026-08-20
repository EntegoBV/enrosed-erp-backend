package be.enrosed.catalog.application.port.out;

import java.io.InputStream;

/**
 * Outbound port for the photo files.
 *
 * Bytes go in unchanged and come out unchanged: no rescaling, no
 * recompression. What the supplier sends is what you get back.
 */
public interface PhotoStorage {

    record Stored(String storageKey, long sizeBytes, Integer widthPx, Integer heightPx) {}

    Stored store(String originalFilename, String contentType, byte[] data);

    /** Stores a prevalidated migration rendition under a deterministic checksum key. */
    default Stored storeKnown(String storageKey, String originalFilename,
                              String contentType, byte[] data) {
        throw new UnsupportedOperationException("Deterministic photo storage is not configured");
    }

    InputStream read(String storageKey);

    void delete(String storageKey);

    boolean exists(String storageKey);
}
