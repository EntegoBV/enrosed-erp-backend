package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/** Keeps the public image list, variant primary image and featured choices on one contract. */
@ApplicationScoped
public class FamilyPhotoPublicationPolicy {
    private final FamilyPhotoVariantResolver variants;
    private final ObjectMapper json;

    public FamilyPhotoPublicationPolicy(FamilyPhotoVariantResolver variants, ObjectMapper json) {
        this.variants = variants;
        this.json = json;
    }

    /**
     * An image is public only when both renditions exist and its optional variant selector
     * resolves to an active family member. Incomplete uploads remain editable in the
     * administrator API without breaking the live catalogue.
     */
    public boolean isPublic(
            ProductFamilyPhotoEntity image, List<ProductEntity> familyMembers) {
        return isPublicAnywhere(image, familyMembers);
    }

    public boolean isPublic(
            ProductFamilyPhotoEntity image, List<ProductEntity> familyMembers,
            CatalogChannel channel) {
        return isSelectedFor(image, channel) && isEligible(image, familyMembers);
    }

    public boolean isPublicAnywhere(
            ProductFamilyPhotoEntity image, List<ProductEntity> familyMembers) {
        return !publishedChannels(image).isEmpty() && isEligible(image, familyMembers);
    }

    public boolean isEligible(
            ProductFamilyPhotoEntity image, List<ProductEntity> familyMembers) {
        if (!hasPublicMetadata(image, json)) return false;
        ProductEntity resolved = variants.resolve(image, familyMembers);
        if (resolved != null) return resolved.active;
        return variants.isFamilyWide(image);
    }

    /** Exact variant images and genuinely family-wide images are valid merchandising choices. */
    public boolean isUsableBy(
            ProductFamilyPhotoEntity image, ProductEntity product,
            List<ProductEntity> familyMembers) {
        return isPublicAnywhere(image, familyMembers)
                && variants.rank(image, product, familyMembers) < 2;
    }

    public boolean isUsableBy(
            ProductFamilyPhotoEntity image, ProductEntity product,
            List<ProductEntity> familyMembers, CatalogChannel channel) {
        return isPublic(image, familyMembers, channel)
                && variants.rank(image, product, familyMembers) < 2;
    }

    /** Effective state; null is the legacy all-channel compatibility value. */
    public List<CatalogChannel> publishedChannels(ProductFamilyPhotoEntity image) {
        return selectedChannels(image, json);
    }

    public boolean isSelectedFor(ProductFamilyPhotoEntity image, CatalogChannel channel) {
        return channel != null && publishedChannels(image).contains(channel);
    }

    public void replacePublishedChannels(
            ProductFamilyPhotoEntity image, Collection<CatalogChannel> requested) {
        if (requested == null) {
            throw new BusinessRuleException("Kies voor welke kanalen de foto gepubliceerd wordt");
        }
        EnumSet<CatalogChannel> channels = EnumSet.noneOf(CatalogChannel.class);
        channels.addAll(requested);
        try {
            image.publishedChannelsJson = json.writeValueAsString(
                    List.of(CatalogChannel.values()).stream().filter(channels::contains).toList());
        } catch (Exception exception) {
            throw new BusinessRuleException("De publicatiekanalen van de foto konden niet worden opgeslagen");
        }
    }

    /**
     * The channels a photo is selected for. Null is the legacy value of rows that predate the
     * channel choice: they were public on every channel, but only once they carried an explicit
     * alt text. They keep exactly that visibility, so a legacy row without an alt stays
     * internal until the publication command stores an explicit channel list.
     */
    public static List<CatalogChannel> selectedChannels(
            ProductFamilyPhotoEntity image, ObjectMapper json) {
        if (image == null) return List.of();
        if (image.publishedChannelsJson == null) {
            return hasExplicitAlt(image, json) ? List.of(CatalogChannel.values()) : List.of();
        }
        if (image.publishedChannelsJson.isBlank()) return List.of();
        try {
            JsonNode values = json.readTree(image.publishedChannelsJson);
            if (values == null || !values.isArray()) return List.of();
            EnumSet<CatalogChannel> selected = EnumSet.noneOf(CatalogChannel.class);
            for (JsonNode value : values) {
                try {
                    selected.add(CatalogChannel.valueOf(value.asText("")));
                } catch (IllegalArgumentException ignored) {
                    // Unknown future or corrupt values fail closed on this version.
                }
            }
            return List.of(CatalogChannel.values()).stream().filter(selected::contains).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static boolean isSelectedFor(
            ProductFamilyPhotoEntity image, CatalogChannel channel, ObjectMapper json) {
        return channel != null && selectedChannels(image, json).contains(channel);
    }

    /**
     * Renditions and dimensions make an image publishable. Explicit alt texts are optional:
     * the public projection generates one from the family name and variant colour
     * ({@link FamilyPhotoAltText}). Unreadable stored alt texts still fail closed.
     */
    public static boolean hasPublicMetadata(
            ProductFamilyPhotoEntity image, ObjectMapper json) {
        if (image == null || blank(image.sourceKey)
                || blank(image.smallStorageKey) || blank(image.largeStorageKey)
                || !positive(image.smallWidthPx) || !positive(image.smallHeightPx)
                || !positive(image.largeWidthPx) || !positive(image.largeHeightPx)) {
            return false;
        }
        if (blank(image.altTextsJson)) return true;
        try {
            JsonNode values = json.readTree(image.altTextsJson);
            return values != null && values.isArray();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** The pre-channel publication rule: at least one non-blank explicit alt text. */
    private static boolean hasExplicitAlt(ProductFamilyPhotoEntity image, ObjectMapper json) {
        if (blank(image.altTextsJson)) return false;
        try {
            JsonNode values = json.readTree(image.altTextsJson);
            if (values == null || !values.isArray()) return false;
            for (JsonNode value : values) {
                if (!blank(value.path("alt").asText(null))) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** The publication command's precondition, with the reason the administrator can fix. */
    public void requireEligible(ProductFamilyPhotoEntity image, List<ProductEntity> familyMembers) {
        if (!isEligible(image, familyMembers)) {
            throw new BusinessRuleException(
                    "Foto kan nog niet gepubliceerd worden: controleer de afmetingen en koppel "
                            + "ze aan een actieve variant of de hele familie");
        }
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
