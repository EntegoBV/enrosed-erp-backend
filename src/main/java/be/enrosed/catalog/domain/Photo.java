package be.enrosed.catalog.domain;

/**
 * A product photo. The file stays in storage at full quality - nothing is
 * rescaled or recompressed, so the photo comes back out of the system
 * usable for print or a webshop.
 */
public record Photo(
        Long id,
        String storageKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Integer widthPx,
        Integer heightPx,
        int position
) {
}
