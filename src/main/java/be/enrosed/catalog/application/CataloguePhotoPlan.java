package be.enrosed.catalog.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which photos the PDF catalogue prints, and in which order.
 *
 * The one place these rules live: the renderer prints what this plan selects and the photo
 * overview reports it, so the screen can never name another photo than the brochure shows.
 * Pure selection: no storage, no encoding. Whether a blob can be read is asked through
 * {@link Usable}; the order of those questions is part of the rules, because a photo that
 * cannot be read still reserves its storage key.
 */
public final class CataloguePhotoPlan {
    private CataloguePhotoPlan() {}

    /** Where a photo is read from: the series gallery or a product's own photo list. */
    public enum Origin { GALLERY, PRODUCT }

    /** Why a photo is one of the series' large photos. */
    public enum Basis { MANUAL_DETAIL, VARIANT_LEAD, GALLERY, OWN_FILLER }

    /** signedId: above zero a series photo, below zero an own photo; null only for a photo without id. */
    public record Ref(Long signedId, String storageKey, Origin origin, boolean catalogueLead) {}

    /** A photo of the series' catalogue gallery, or a series-level choice in the same shape. */
    public record GalleryPhoto(Long signedId, String storageKey, int position, Long variantProductId) {}

    /** One row of a colour's photo list; familyPhotoId names the series photo it was projected from. */
    public record VariantPhoto(Long id, String storageKey, int position, Long familyPhotoId,
                               boolean leadsCatalogue) {
        public boolean inherited() {
            return familyPhotoId != null;
        }
    }

    /** A colour with its photos in the product's own order. */
    public record Variant(Long productId, List<VariantPhoto> photos) {
        public Variant {
            photos = photos == null ? List.of() : List.copyOf(photos);
        }
    }

    /**
     * productId: the colour whose choice or own photo this is; for a gallery photo or a series
     * choice the colour it belongs to, null when it is for all colours.
     */
    public record DetailEntry(Ref ref, Basis basis, Long productId) {}

    /**
     * detail: the large photos in selection order, before the three-photo layout moves its
     * strongest photo forward. manualOverview: the series' thumbnail choice, null when the
     * thumbnail follows the first large photo. explicitSelection: a choice was used, so a
     * larger budget is not filled up.
     */
    public record FamilySelection(List<DetailEntry> detail, Ref manualOverview, boolean explicitSelection) {
        public FamilySelection {
            detail = List.copyOf(detail);
        }
    }

    @FunctionalInterface
    public interface Usable {
        boolean test(Ref ref);
    }

    /**
     * The series' large photos for the brochure and its thumbnail choice.
     *
     * @param hasFamily false for a product printed without series content
     * @param gallery the series' catalogue gallery in series order
     * @param manualDetail the series' choice for the large photo, or null
     * @param manualOverview the series' choice for the range thumbnail, or null
     * @param selected the colours in the export, in the requested order
     * @param allowed the photo budget of this series; zero prints nothing
     */
    public static FamilySelection family(boolean hasFamily, List<GalleryPhoto> gallery,
                                         GalleryPhoto manualDetail, GalleryPhoto manualOverview,
                                         List<Variant> selected, int allowed, Usable usable) {
        List<DetailEntry> detail = new ArrayList<>();
        Set<String> imageKeys = new LinkedHashSet<>();
        Set<Long> selectedIds = selected.stream().map(Variant::productId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Set<Long> allowedFamilyPhotoIds = !hasFamily ? Set.of() : gallery.stream()
                .filter(photo -> photo.variantProductId() == null
                        || selectedIds.contains(photo.variantProductId()))
                .map(GalleryPhoto::signedId)
                .collect(java.util.stream.Collectors.toSet());
        Ref detailChoice = allowed > 0 && hasFamily
                ? selectedFamilyPhoto(manualDetail, selectedIds, usable) : null;
        if (detailChoice != null) {
            detail.add(new DetailEntry(detailChoice, Basis.MANUAL_DETAIL, manualDetail.variantProductId()));
            imageKeys.add(detailChoice.storageKey());
        }
        /* A photo the buyer chose to open the catalogue with goes first. An inherited
           lead still has to belong to this channel and the selected variants. */
        if (allowed > 0) {
            for (Variant variant : selected) {
                if (detail.size() >= allowed) break;
                for (VariantPhoto photo : variant.photos()) {
                    if (detail.size() >= allowed) break;
                    if (!photo.leadsCatalogue()) continue;
                    if (photo.inherited() && !allowedFamilyPhotoIds.contains(photo.familyPhotoId())) continue;
                    Ref ref = productRef(photo);
                    if (ref != null && imageKeys.add(ref.storageKey()) && usable.test(ref)) {
                        detail.add(new DetailEntry(ref, Basis.VARIANT_LEAD, variant.productId()));
                    }
                }
            }
        }
        // A usable manual detail or variant catalogue lead is an authoritative selection.
        // Larger photo limits must not bring older unselected backgrounds back into the PDF.
        boolean explicitSelection = !detail.isEmpty();
        if (!explicitSelection && allowed > 0 && hasFamily) {
            for (GalleryPhoto photo : gallery) {
                if (detail.size() >= allowed) break;
                if (photo.variantProductId() != null && !selectedIds.contains(photo.variantProductId())) {
                    continue;
                }
                Ref ref = familyRef(photo);
                if (ref != null && imageKeys.add(ref.storageKey()) && usable.test(ref)) {
                    detail.add(new DetailEntry(ref, Basis.GALLERY, photo.variantProductId()));
                }
            }
        }
        if (!explicitSelection && allowed > 0 && detail.size() < allowed) {
            for (Variant variant : selected) {
                for (VariantPhoto photo : variant.photos()) {
                    /* Inherited photos may be internal or selected for another channel.
                       Catalogue-family photos were already resolved through the channel-safe
                       family projection above. */
                    if (photo.inherited()) continue;
                    Ref ref = productRef(photo);
                    if (ref != null && imageKeys.add(ref.storageKey()) && usable.test(ref)) {
                        detail.add(new DetailEntry(ref, Basis.OWN_FILLER, variant.productId()));
                    }
                    if (detail.size() >= allowed) break;
                }
                if (detail.size() >= allowed) break;
            }
        }
        Ref overviewChoice = allowed > 0 && hasFamily
                ? selectedFamilyPhoto(manualOverview, selectedIds, usable) : null;
        return new FamilySelection(detail, overviewChoice, explicitSelection);
    }

    /**
     * The photos that may illustrate one colour in the brochure's colour strip, best first and
     * unique by storage key. Not yet tested for usability: the strip prints the first one that
     * can be read. Never borrows a generic series photograph to illustrate a particular colour.
     */
    public static List<Ref> variantCandidates(Variant variant, boolean hasFamily, List<GalleryPhoto> gallery) {
        List<GalleryPhoto> exactPhotos = !hasFamily || variant.productId() == null
                ? List.of() : gallery.stream()
                .filter(photo -> variant.productId().equals(photo.variantProductId()))
                .sorted(Comparator.comparingInt(GalleryPhoto::position))
                .toList();
        List<Ref> candidates = new ArrayList<>();
        List<VariantPhoto> productPhotos = variant.photos().stream()
                .sorted(Comparator.comparingInt(VariantPhoto::position)).toList();
        /* An explicitly chosen catalogue image replaces an older original in the
           colour strip too. Inherited leads must still match this exact variant
           and the canonical image published for the catalogue channel. */
        for (VariantPhoto photo : productPhotos) {
            if (!photo.leadsCatalogue()) continue;
            if (!photo.inherited()) {
                candidates.add(productRef(photo));
                continue;
            }
            /* The projection can carry a lead from another colour or an internal channel.
               Both canonical row identity and the exact published blob must agree. */
            exactPhotos.stream().filter(published ->
                            photo.familyPhotoId().equals(published.signedId())
                                    && Objects.equals(photo.storageKey(), published.storageKey()))
                    .findFirst().ifPresent(published -> candidates.add(new Ref(
                            published.signedId(), published.storageKey(), Origin.GALLERY, true)));
        }
        productPhotos.stream().filter(photo -> !photo.inherited())
                .forEach(photo -> candidates.add(productRef(photo)));
        exactPhotos.forEach(photo -> candidates.add(familyRef(photo)));
        Map<String, Ref> unique = new LinkedHashMap<>();
        for (Ref ref : candidates) {
            if (ref != null && present(ref.storageKey())) unique.putIfAbsent(ref.storageKey(), ref);
        }
        return List.copyOf(unique.values());
    }

    /**
     * The photos of one colour's row in the compact catalogue.
     *
     * @param catalogueGalleryIds ids of every catalogue gallery photo in the export
     */
    public static List<Ref> compactRow(Variant variant, Set<Long> catalogueGalleryIds, int allowed,
                                       Usable usable) {
        List<Ref> refs = new ArrayList<>();
        Set<String> imageKeys = new LinkedHashSet<>();
        /* Inherited photos remain channel-filtered. Once a usable catalogue choice exists,
           the requested count caps that selection; it does not refill it with old originals. */
        List<VariantPhoto> eligiblePhotos = variant.photos().stream()
                .filter(photo -> !photo.inherited()
                        || catalogueGalleryIds.contains(photo.familyPhotoId())).toList();
        List<VariantPhoto> catalogueLeads = allowed <= 0 ? List.of() : eligiblePhotos.stream()
                .filter(VariantPhoto::leadsCatalogue)
                .filter(photo -> {
                    Ref ref = productRef(photo);
                    return ref != null && usable.test(ref);
                }).toList();
        for (VariantPhoto photo : catalogueLeads.isEmpty() ? eligiblePhotos : catalogueLeads) {
            if (refs.size() >= allowed) break;
            Ref ref = productRef(photo);
            if (ref != null && imageKeys.add(ref.storageKey()) && usable.test(ref)) {
                refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }

    private static Ref productRef(VariantPhoto photo) {
        return photo == null || !present(photo.storageKey())
                ? null : new Ref(photo.inherited() ? photo.familyPhotoId()
                        : photo.id() == null ? null : -photo.id(),
                        photo.storageKey(), Origin.PRODUCT, photo.leadsCatalogue());
    }

    private static Ref familyRef(GalleryPhoto photo) {
        return photo == null || !present(photo.storageKey())
                ? null : new Ref(photo.signedId(), photo.storageKey(), Origin.GALLERY, false);
    }

    /** A series-level choice counts while its colour is selected and its blob can be read. */
    private static Ref selectedFamilyPhoto(GalleryPhoto photo, Set<Long> selectedIds, Usable usable) {
        if (photo == null || (photo.variantProductId() != null
                && !selectedIds.contains(photo.variantProductId()))) return null;
        Ref ref = familyRef(photo);
        return ref != null && usable.test(ref)
                ? new Ref(ref.signedId(), ref.storageKey(), ref.origin(), true) : null;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
