package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.PublicationState;

import java.util.List;

/**
 * Everything the ERP photo section of one product needs, in one read: its own photos, the
 * series photos of its family (all colours) and which photo fills which role.
 *
 * Photos are addressed by key: {@code "F<familyPhotoId>"} for a series photo and
 * {@code "P<productPhotoId>"} for an own photo. The signed ids used by the website and the
 * catalogue map onto the same keys (positive n is F n, negative -n is P n).
 */
public record ProductPhotoOverviewDto(
        long productId,
        Long familyId,
        String familyName,
        String variantLabel,
        PublicationState familyWebsiteStatus,
        List<PhotoDto> photos,
        /** Per colour: first photo on the website, on quotes and invoices. */
        RoleChoiceDto main,
        /** Per family: the website's quote page photo; null without public website images. */
        RoleChoiceDto quote,
        /** Per colour: the catalogue lead; null while the catalogue picks automatically. */
        RoleChoiceDto catalogueVariant,
        RoleChoiceDto catalogueOverview,
        RoleChoiceDto catalogueDetail,
        String catalogueDetailSize
) {
    public enum Kind { SERIES, OWN }

    /** Relative to this product; an own photo is always THIS_VARIANT. */
    public enum Scope { THIS_VARIANT, ALL_VARIANTS, OTHER_VARIANT }

    public enum Role { MAIN, QUOTE, CATALOGUE_VARIANT, CATALOGUE_OVERVIEW, CATALOGUE_DETAIL }

    /** Why the website shows the photo: published series photo, own lead, or own fallback. */
    public enum WebsiteReason { PUBLISHED, LEAD, FALLBACK }

    public record PhotoDto(
            String key,
            Kind kind,
            Long familyPhotoId,
            /** Own: the row itself. Series: this product's projection row, null for other colours. */
            Long productPhotoId,
            Scope scope,
            Long variantProductId,
            String variantLabel,
            String originalFilename,
            String contentType,
            Integer widthPx,
            Integer heightPx,
            long sizeBytes,
            String smallUrl,
            String mediumUrl,
            String largeUrl,
            String downloadUrl,
            /** Effective public visibility under the projection rules, not the family status. */
            VisibilityDto visibility,
            WebsiteReason websiteReason,
            /** Series: channels may be switched on. Own photos are never published directly. */
            boolean publishable,
            List<Role> roles,
            /** Own only: the series photo with the same original (size, dimensions, type). */
            String duplicateOfKey,
            Integer familyPosition,
            Integer ownPosition,
            /**
             * Series only: the channels stored on the family photo (a legacy row without a
             * channel choice lists all three once it has an alt). Differs from
             * {@code visibility} when the projection hides a selected photo; a channel switch
             * starts from this list so no stored channel is dropped. Null for own photos.
             */
            List<CatalogChannel> publishedChannels) {}

    public record VisibilityDto(boolean website, boolean catalogue, boolean orderApp) {}

    /** {@code explicit} is false while the automatic rule chose the photo. */
    public record RoleChoiceDto(String key, boolean explicit) {}
}
