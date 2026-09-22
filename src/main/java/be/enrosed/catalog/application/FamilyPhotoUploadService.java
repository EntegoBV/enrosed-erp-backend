package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.application.port.out.PhotoStorage;
import jakarta.enterprise.context.ApplicationScoped;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns validated bytes into a series photo ("reeksfoto") of a family.
 *
 * The content checksum is the identity: the same picture uploaded twice stays one family
 * photo. A new photo is internal (no channels, no alt texts) until someone publishes it.
 * The caller owns the family lock, the projection sync and the activity entry.
 */
@ApplicationScoped
public class FamilyPhotoUploadService {
    private final PhotoStorage photoStorage;
    private final PhotoRenditionService photoRenditions;
    private final FamilyImageVariantService familyImageVariants;

    public FamilyPhotoUploadService(PhotoStorage photoStorage,
                                    PhotoRenditionService photoRenditions,
                                    FamilyImageVariantService familyImageVariants) {
        this.photoStorage = photoStorage;
        this.photoRenditions = photoRenditions;
        this.familyImageVariants = familyImageVariants;
    }

    /** {@code created} is false when the family already held these exact bytes. */
    public record Stored(ProductFamilyPhotoEntity photo, boolean created) {}

    public static String sourceKey(String sha256) {
        return "admin-" + sha256;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is niet beschikbaar", exception);
        }
    }

    /**
     * The family photo holding exactly these bytes: an earlier upload, or an imported photo
     * whose original (the large rendition is never recompressed) has the same checksum.
     */
    public static Optional<ProductFamilyPhotoEntity> existing(
            ProductFamilyEntity family, String sha256) {
        String sourceKey = sourceKey(sha256);
        return family.photos.stream().filter(photo -> sourceKey.equals(photo.sourceKey)).findFirst()
                .or(() -> family.photos.stream()
                        .filter(photo -> sha256.equalsIgnoreCase(photo.largeSha256))
                        .min(java.util.Comparator.comparingInt(photo -> photo.position)));
    }

    /**
     * Appends the photo at the end of the series, for one variant or (variant null) the whole
     * family. Legacy variant hints are kept only for family-wide uploads, as before.
     */
    public Stored store(ProductFamilyEntity family, PhotoUploadPolicy.ValidatedPhoto upload,
                        ProductEntity variant, String variantExternalId, String variantColor) {
        String checksum = sha256(upload.bytes());
        Optional<ProductFamilyPhotoEntity> existing = existing(family, checksum);
        if (existing.isPresent()) return new Stored(existing.get(), false);

        /* Decode and render before storing either blob: corrupt images cannot leave a half upload. */
        PhotoRenditionService.Rendition small = photoRenditions.small(upload);
        String largeStorageKey = "sha256-" + checksum + extension(upload.contentType());
        PhotoStorage.Stored largeStored = photoStorage.storeKnown(
                largeStorageKey, upload.originalFilename(), upload.contentType(), upload.bytes());
        String smallStorageKey = "sha256-" + small.sha256() + small.extension();
        PhotoStorage.Stored smallStored = Objects.equals(smallStorageKey, largeStorageKey)
                ? largeStored
                : photoStorage.storeKnown(
                        smallStorageKey, small.filename(), small.contentType(), small.bytes());
        ProductFamilyPhotoEntity photo = new ProductFamilyPhotoEntity();
        photo.family = family;
        photo.sourceKey = sourceKey(checksum);
        photo.originalFilename = upload.originalFilename();
        photo.originalWidthPx = largeStored.widthPx();
        photo.originalHeightPx = largeStored.heightPx();
        photo.smallStorageKey = smallStorageKey;
        photo.smallContentType = small.contentType();
        photo.smallSha256 = small.sha256();
        photo.smallSizeBytes = smallStored.sizeBytes();
        photo.smallWidthPx = smallStored.widthPx();
        photo.smallHeightPx = smallStored.heightPx();
        photo.smallRenditionVersion = PhotoRenditionService.POLICY_VERSION;
        photo.largeStorageKey = largeStorageKey;
        photo.largeContentType = upload.contentType();
        photo.largeSha256 = checksum;
        photo.largeSizeBytes = largeStored.sizeBytes();
        photo.largeWidthPx = largeStored.widthPx();
        photo.largeHeightPx = largeStored.heightPx();
        photo.position = family.photos.size();
        if (variant == null) {
            photo.variantExternalId = optional(variantExternalId);
            photo.variantColor = optional(variantColor);
        } else {
            familyImageVariants.assign(photo, variant);
        }
        photo.altTextSource = "ADMIN";
        photo.altTextsJson = "[]";
        /* Uploading is an internal asset action. Publication is a separate, visible command. */
        photo.publishedChannelsJson = "[]";
        family.photos.add(photo);
        return new Stored(photo, true);
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
