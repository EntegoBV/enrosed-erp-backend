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
        String catalogueDetailSize,
        /** What every place really shows now; the fields above keep reporting the stored roles. */
        PlacesDto places,
        /** Photos a place refers to that are not in {@code photos}: another colour's own photo. */
        List<OtherPhotoDto> otherPhotos,
        /**
         * The series is active, published on the website, has at least one website photo and at
         * least one active colour that is not a demo piece.
         */
        boolean seriesLiveOnWebsite,
        boolean colourOnWebsite,
        /** The one export the three brochure places are computed for. */
        CatalogueAssumptionDto catalogueAssumption
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
            List<CatalogChannel> publishedChannels,
            /** Every colour of the series that stored this photo as its website or catalogue choice. */
            List<ChosenForDto> chosenFor) {}

    public record VisibilityDto(boolean website, boolean catalogue, boolean orderApp) {}

    /** {@code explicit} is false while the automatic rule chose the photo. */
    public record RoleChoiceDto(String key, boolean explicit) {}

    /**
     * CHOSEN: the stored choice is the photo shown. AUTOMATIC: nothing stored, something is
     * shown. CHOSEN_NOT_SHOWN: a choice is stored, the place shows another photo or nothing.
     * NONE: nothing stored, nothing shown.
     */
    public enum PlaceState { CHOSEN, AUTOMATIC, CHOSEN_NOT_SHOWN, NONE }

    /** Why a place shows what it shows; null only for CHOSEN. */
    public enum PlaceReason {
        NO_SERIES, NO_PHOTO, COLOUR_NOT_ACTIVE, SERIES_NOT_ACTIVE,
        OWN_COLOUR_SERIES_PHOTO, ALL_COLOURS_SERIES_PHOTO, OWN_PHOTO_FALLBACK,
        FIRST_IN_SERIES_ORDER, FIRST_SERIES_PHOTO, FIRST_OWN_PHOTO,
        FIRST_COLOUR_WEBSITE_PHOTO, OWN_PHOTO_FIRST,
        THIS_COLOUR_LEAD, OTHER_COLOUR_LEAD_OVERRIDES, OWN_PHOTO_FILLER,
        SAME_AS_LARGE_PHOTO,
        NOT_PUBLISHED_FOR_WEBSITE, NOT_PUBLISHED_FOR_CATALOGUE, NOT_PUBLISHABLE,
        ALL_COLOURS_PHOTO_NOT_USED_PER_COLOUR, SINGLE_COLOUR_SERIES,
        CHOICE_NOT_AVAILABLE, PHOTO_UNREADABLE }

    /** COLOUR: the choice is for this colour only. SERIES: for every colour of the series. */
    public enum PlaceScope { COLOUR, SERIES }

    /**
     * One place a photo is shown. {@code shownKey} is the photo really shown now, {@code
     * chosenKey} the stored choice even when it is not shown; both resolve in {@code photos}
     * or {@code otherPhotos}. {@code role} is the role to send to change this place, null when
     * it cannot be chosen. {@code relatedProductId} is the colour the shown photo belongs to,
     * or whose choice decides; null for a photo of all colours.
     */
    public record PlaceDto(String shownKey, String chosenKey, PlaceState state, PlaceReason reason,
                           Role role, PlaceScope scope, Long relatedProductId, String relatedLabel,
                           boolean thisColour) {}

    public record PlacesDto(
            /** Product page with this colour selected; this colour's row on the quote page. */
            PlaceDto websiteColour,
            /** The photo the series' product page opens with: first in series order. */
            PlaceDto websiteOpening,
            /** Quotes, invoices, customer portal and ERP lists. */
            PlaceDto documents,
            PlaceDto websiteQuote,
            /** Brochure: this colour's picture in the colour strip. */
            PlaceDto catalogueColour,
            /** Brochure: the series' large photo. */
            PlaceDto catalogueDetail,
            /** Brochure: the thumbnail in the range table. */
            PlaceDto catalogueOverview,
            /** Compact catalogue: this colour's row picture. */
            PlaceDto catalogueCompactRow) {}

    public record OtherPhotoDto(String key, long productId, String variantLabel,
                                String originalFilename, Integer widthPx, Integer heightPx,
                                String smallUrl, String mediumUrl, String largeUrl) {}

    public record ChosenForDto(long productId, String variantLabel, Role role) {}

    /**
     * The brochure with photos on, one photo per series and every active colour in variant
     * order. An export of fewer colours, another order or more photos can print otherwise.
     */
    public record CatalogueAssumptionDto(String layout, int photosPerSeries,
                                         List<Long> productIds, boolean includesThisColour) {}
}
