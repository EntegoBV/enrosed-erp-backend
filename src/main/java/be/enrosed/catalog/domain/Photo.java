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
        int position,
        /** Canonical family-gallery row that projected this photo; null means product-owned. */
        Long familyPhotoId,
        /** The channels this photo opens: the website, the printed catalogue. Never null. */
        java.util.Set<PhotoRole> leadFor
) {
    public Photo {
        leadFor = leadFor == null ? java.util.Set.of() : java.util.Set.copyOf(leadFor);
    }

    /** Compatibility constructor for callers written before a photo could lead a channel. */
    public Photo(
            Long id, String storageKey, String originalFilename, String contentType,
            long sizeBytes, Integer widthPx, Integer heightPx, int position, Long familyPhotoId) {
        this(id, storageKey, originalFilename, contentType,
                sizeBytes, widthPx, heightPx, position, familyPhotoId, java.util.Set.of());
    }

    /** Compatibility constructor for product-owned uploads and older callers. */
    public Photo(
            Long id, String storageKey, String originalFilename, String contentType,
            long sizeBytes, Integer widthPx, Integer heightPx, int position) {
        this(id, storageKey, originalFilename, contentType,
                sizeBytes, widthPx, heightPx, position, null, java.util.Set.of());
    }

    public boolean leads(PhotoRole role) {
        return leadFor.contains(role);
    }

    public Photo withLeadFor(java.util.Set<PhotoRole> roles) {
        return new Photo(id, storageKey, originalFilename, contentType, sizeBytes, widthPx, heightPx,
                position, familyPhotoId, roles);
    }

    public boolean inherited() {
        return familyPhotoId != null;
    }
}
