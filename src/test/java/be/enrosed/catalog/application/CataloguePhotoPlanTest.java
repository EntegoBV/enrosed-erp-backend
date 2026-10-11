package be.enrosed.catalog.application;

import be.enrosed.catalog.application.CataloguePhotoPlan.FamilySelection;
import be.enrosed.catalog.application.CataloguePhotoPlan.GalleryPhoto;
import be.enrosed.catalog.application.CataloguePhotoPlan.Origin;
import be.enrosed.catalog.application.CataloguePhotoPlan.Ref;
import be.enrosed.catalog.application.CataloguePhotoPlan.Usable;
import be.enrosed.catalog.application.CataloguePhotoPlan.Variant;
import be.enrosed.catalog.application.CataloguePhotoPlan.VariantPhoto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue's photo rules on their own. Photos are written as signed id, why they were
 * selected and whose they are; the same cases run through the renderer in
 * PdfCatalogPhotoSelectionGoldenTest.
 */
class CataloguePhotoPlanTest {

    private static final long RED = 1L;
    private static final long PINK = 2L;
    private static final long WHITE = 3L;
    private static final Usable ALWAYS = ref -> true;

    @Test
    void withoutAChoiceTheGalleryIsTakenInSeriesOrderUpToTheBudget() {
        List<GalleryPhoto> gallery = List.of(
                gallery(14, "white", 0, WHITE), gallery(11, "all", 1, null),
                gallery(12, "red", 2, RED), gallery(13, "pink", 3, PINK));
        List<Variant> selected = List.of(
                variant(RED, inherited(111, "all", 0, 11), inherited(112, "red", 1, 12)),
                variant(PINK, inherited(211, "all", 0, 11), inherited(213, "pink", 1, 13)));

        FamilySelection one = CataloguePhotoPlan.family(true, gallery, null, null, selected, 1, ALWAYS);
        FamilySelection many = CataloguePhotoPlan.family(true, gallery, null, null, selected, 8, ALWAYS);

        assertEquals(List.of("11 GALLERY all"), detail(one));
        assertEquals(new Ref(11L, "all", Origin.GALLERY, false), one.detail().getFirst().ref());
        // The white colour is not in the export; inherited rows never fill a budget.
        assertEquals(List.of("11 GALLERY all", "12 GALLERY 1", "13 GALLERY 2"), detail(many));
        assertFalse(many.explicitSelection());
        assertNull(many.manualOverview());
    }

    @Test
    void aColoursChoiceIsTheWholeSelection() {
        List<GalleryPhoto> gallery = List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED), gallery(13, "pink", 2, PINK));
        List<Variant> selected = List.of(
                variant(RED, inherited(111, "all", 0, 11), inherited(112, "red", 1, 12)),
                variant(PINK, inherited(211, "all", 0, 11), lead(inherited(213, "pink", 1, 13))));

        FamilySelection selection = CataloguePhotoPlan.family(true, gallery, null, null, selected, 3, ALWAYS);

        assertEquals(List.of("13 VARIANT_LEAD 2"), detail(selection));
        // The colour's row is read as a product photo, not through the gallery.
        assertEquals(new Ref(13L, "pink", Origin.PRODUCT, true), selection.detail().getFirst().ref());
        assertTrue(selection.explicitSelection());
    }

    @Test
    void anInheritedChoiceMustBeACataloguePhotoOfASelectedColourOrOfAllColours() {
        List<GalleryPhoto> gallery = List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED), gallery(14, "white", 2, WHITE));
        Variant internal = variant(RED, lead(inherited(116, "internal", 0, 16)));
        Variant otherColour = variant(RED, lead(inherited(114, "white", 0, 14)));
        Variant allColours = variant(PINK, lead(inherited(211, "all", 0, 11)));

        assertEquals(List.of("11 GALLERY all", "12 GALLERY 1"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, List.of(internal), 2, ALWAYS)));
        assertEquals(List.of("11 GALLERY all", "12 GALLERY 1"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, List.of(otherColour), 2, ALWAYS)));
        assertEquals(List.of("14 VARIANT_LEAD 1"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, List.of(otherColour, variant(WHITE)), 2, ALWAYS)));
        assertEquals(List.of("11 VARIANT_LEAD 2"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, List.of(variant(RED), allColours), 2, ALWAYS)));
        // Without series content no inherited row can be trusted.
        assertEquals(List.of(), detail(CataloguePhotoPlan.family(
                false, gallery, null, null, List.of(allColours), 2, ALWAYS)));
    }

    @Test
    void choicesOfSeveralColoursFollowTheRequestedOrderWithinTheBudget() {
        Variant red = variant(RED, lead(own(501, "red-chosen", 0)), lead(own(502, "red-second", 1)));
        Variant pink = variant(PINK, lead(own(601, "pink-chosen", 0)));
        List<GalleryPhoto> gallery = List.of(gallery(11, "all", 0, null));

        assertEquals(List.of("-501 VARIANT_LEAD 1"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, List.of(red, pink), 1, ALWAYS)));
        assertEquals(List.of("-601 VARIANT_LEAD 2"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, List.of(pink, red), 1, ALWAYS)));
        assertEquals(List.of("-601 VARIANT_LEAD 2", "-501 VARIANT_LEAD 1"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, List.of(pink, red), 2, ALWAYS)));
        assertEquals(List.of("-501 VARIANT_LEAD 1", "-502 VARIANT_LEAD 1", "-601 VARIANT_LEAD 2"),
                detail(CataloguePhotoPlan.family(true, gallery, null, null, List.of(red, pink), 8, ALWAYS)));
    }

    @Test
    void theSeriesChoiceForTheLargePhotoGoesFirstAndReservesItsBlob() {
        List<GalleryPhoto> gallery = List.of(gallery(11, "all", 0, null), gallery(12, "red", 1, RED));
        GalleryPhoto detail = gallery(-601, "pink-own", -1, PINK);
        GalleryPhoto overview = gallery(12, "red", -1, RED);
        List<Variant> selected = List.of(
                variant(RED, lead(own(501, "red-chosen", 0))),
                variant(PINK, lead(own(601, "pink-own", 0))));

        FamilySelection one = CataloguePhotoPlan.family(true, gallery, detail, overview, selected, 1, ALWAYS);
        FamilySelection many = CataloguePhotoPlan.family(true, gallery, detail, overview, selected, 8, ALWAYS);

        assertEquals(List.of("-601 MANUAL_DETAIL 2"), detail(one));
        assertEquals(new Ref(-601L, "pink-own", Origin.GALLERY, true), one.detail().getFirst().ref());
        assertEquals(new Ref(12L, "red", Origin.GALLERY, true), one.manualOverview());
        assertEquals(List.of("-601 MANUAL_DETAIL 2", "-501 VARIANT_LEAD 1"), detail(many));
        assertTrue(many.explicitSelection());
    }

    @Test
    void aSeriesChoiceCountsOnlyForASelectedColourAndAReadablePhoto() {
        List<GalleryPhoto> gallery = List.of(gallery(11, "all", 0, null), gallery(14, "white", 1, WHITE));
        GalleryPhoto white = gallery(14, "white", -1, WHITE);
        GalleryPhoto all = gallery(11, "all", -1, null);
        List<Variant> red = List.of(variant(RED));

        FamilySelection notSelected = CataloguePhotoPlan.family(true, gallery, white, white, red, 2, ALWAYS);
        assertEquals(List.of("11 GALLERY all"), detail(notSelected));
        assertNull(notSelected.manualOverview());
        assertFalse(notSelected.explicitSelection());

        // An unreadable choice does not reserve its blob: the automatic pass still asks for it.
        List<String> asked = new ArrayList<>();
        FamilySelection unreadable = CataloguePhotoPlan.family(true, gallery, all, all, red, 2, ref -> {
            asked.add(ref.storageKey());
            return false;
        });
        assertEquals(List.of(), detail(unreadable));
        assertNull(unreadable.manualOverview());
        assertEquals(List.of("all", "all", "all"), asked);

        // The thumbnail choice alone leaves the large photos automatic.
        FamilySelection thumbnailOnly = CataloguePhotoPlan.family(true, gallery, null, all, red, 2, ALWAYS);
        assertEquals(List.of("11 GALLERY all"), detail(thumbnailOnly));
        assertEquals(new Ref(11L, "all", Origin.GALLERY, true), thumbnailOnly.manualOverview());
        assertFalse(thumbnailOnly.explicitSelection());

        FamilySelection withoutSeries = CataloguePhotoPlan.family(false, gallery, all, all, red, 2, ALWAYS);
        assertEquals(List.of(), detail(withoutSeries));
        assertNull(withoutSeries.manualOverview());
    }

    @Test
    void aLargerBudgetIsFilledWithOwnPhotosInColourOrder() {
        List<GalleryPhoto> gallery = List.of(gallery(17, "gone", 0, null), gallery(11, "all", 1, null));
        List<Variant> selected = List.of(
                variant(RED, own(501, "first", 0), own(503, "lost", 1), inherited(111, "all", 2, 11),
                        own(502, "second", 3)),
                variant(PINK, own(601, "third", 0), own(604, "first", 1), own(602, "fourth", 2)));
        Usable usable = ref -> !Set.of("gone", "lost").contains(ref.storageKey());

        assertEquals(List.of("11 GALLERY all"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, selected, 1, usable)));
        assertEquals(List.of("11 GALLERY all", "-501 OWN_FILLER 1"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, selected, 2, usable)));
        assertEquals(List.of("11 GALLERY all", "-501 OWN_FILLER 1", "-502 OWN_FILLER 1", "-601 OWN_FILLER 2"),
                detail(CataloguePhotoPlan.family(true, gallery, null, null, selected, 4, usable)));
        // The pink colour's second photo is the red colour's first blob again.
        assertEquals(List.of("11 GALLERY all", "-501 OWN_FILLER 1", "-502 OWN_FILLER 1", "-601 OWN_FILLER 2",
                        "-602 OWN_FILLER 2"),
                detail(CataloguePhotoPlan.family(true, gallery, null, null, selected, 8, usable)));
        FamilySelection withoutSeries = CataloguePhotoPlan.family(false, gallery, null, null, selected, 2, usable);
        assertEquals(List.of("-501 OWN_FILLER 1", "-502 OWN_FILLER 1"), detail(withoutSeries));
        assertEquals(new Ref(-501L, "first", Origin.PRODUCT, false), withoutSeries.detail().getFirst().ref());
        assertFalse(withoutSeries.explicitSelection());
    }

    @Test
    void anUnreadableChoiceKeepsItsBlobReservedButDoesNotStopTheAutomaticSelection() {
        List<GalleryPhoto> gallery = List.of(gallery(11, "shared", 0, RED), gallery(12, "all", 1, null));
        List<Variant> selected = List.of(variant(RED, lead(own(501, "shared", 0)), own(502, "first", 1)));
        // Only the product's copy cannot be read; the series photo with the same blob is not asked again.
        Usable usable = ref -> !(ref.origin() == Origin.PRODUCT && ref.storageKey().equals("shared"));

        FamilySelection selection = CataloguePhotoPlan.family(true, gallery, null, null, selected, 3, usable);

        assertEquals(List.of("12 GALLERY all", "-502 OWN_FILLER 1"), detail(selection));
        assertFalse(selection.explicitSelection());
    }

    @Test
    void aSeriesPhotoAndAnOwnPhotoSharingOneBlobAreSelectedOnce() {
        List<GalleryPhoto> gallery = List.of(gallery(11, "seed", 0, RED), gallery(13, "pink", 1, PINK));
        Variant red = variant(RED, own(501, "seed", 0), inherited(111, "seed", 1, 11));
        Variant redChosen = variant(RED, lead(own(501, "seed", 0)), lead(inherited(111, "seed", 1, 11)));
        Variant pink = variant(PINK, inherited(213, "pink", 0, 13));

        assertEquals(List.of("11 GALLERY 1", "13 GALLERY 2"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, List.of(red, pink), 3, ALWAYS)));
        assertEquals(List.of("-501 VARIANT_LEAD 1"), detail(CataloguePhotoPlan.family(
                true, gallery, null, null, List.of(redChosen, pink), 3, ALWAYS)));
        assertEquals(List.of(new Ref(-501L, "seed", Origin.PRODUCT, false)),
                CataloguePhotoPlan.variantCandidates(red, true, gallery));
        assertEquals(List.of(new Ref(-501L, "seed", Origin.PRODUCT, false)),
                CataloguePhotoPlan.compactRow(red, Set.of(11L, 13L), 3, ALWAYS));
    }

    @Test
    void withoutABudgetNothingIsSelectedAndNoPhotoIsRead() {
        List<GalleryPhoto> gallery = List.of(gallery(11, "all", 0, null));
        GalleryPhoto choice = gallery(11, "all", -1, null);
        Variant red = variant(RED, lead(own(501, "red-chosen", 0)));
        Usable never = ref -> {
            throw new AssertionError("no photo may be read without a budget");
        };

        FamilySelection selection = CataloguePhotoPlan.family(true, gallery, choice, choice, List.of(red), 0, never);

        assertEquals(List.of(), detail(selection));
        assertNull(selection.manualOverview());
        assertFalse(selection.explicitSelection());
        assertEquals(List.of(), CataloguePhotoPlan.compactRow(red, Set.of(11L), 0, never));
    }

    @Test
    void theColourStripPrefersTheChoiceThenOwnPhotosThenTheColoursSeriesPhotos() {
        List<GalleryPhoto> gallery = List.of(
                gallery(11, "all", 0, null), gallery(14, "pink-late", 5, PINK),
                gallery(13, "pink-early", 2, PINK), gallery(12, "red", 3, RED));
        Variant pink = variant(PINK,
                own(602, "own-late", 4), inherited(211, "all", 0, 11), own(601, "own-early", 1),
                lead(inherited(214, "pink-late", 3, 14)), inherited(213, "pink-early", 2, 13),
                own(603, " ", 6));

        assertEquals(List.of(
                        new Ref(14L, "pink-late", Origin.GALLERY, true),
                        new Ref(-601L, "own-early", Origin.PRODUCT, false),
                        new Ref(-602L, "own-late", Origin.PRODUCT, false),
                        new Ref(13L, "pink-early", Origin.GALLERY, false)),
                CataloguePhotoPlan.variantCandidates(pink, true, gallery));
        // Without series content, or for a colour without id, only own photos remain.
        assertEquals(List.of(
                        new Ref(-601L, "own-early", Origin.PRODUCT, false),
                        new Ref(-602L, "own-late", Origin.PRODUCT, false)),
                CataloguePhotoPlan.variantCandidates(pink, false, gallery));
        assertEquals(List.of(new Ref(-601L, "own-early", Origin.PRODUCT, false)),
                CataloguePhotoPlan.variantCandidates(
                        variant(null, own(601, "own-early", 0)), true, gallery));
    }

    @Test
    void aStripChoiceMustBeThisColoursCatalogueSeriesPhotoWithTheSameBlob() {
        List<GalleryPhoto> gallery = List.of(
                gallery(11, "all", 0, null), gallery(12, "red", 1, RED), gallery(13, "pink", 2, PINK));

        // An all-colours photo, another colour's photo and a stale blob never become the choice.
        assertEquals(List.of(new Ref(13L, "pink", Origin.GALLERY, false)),
                CataloguePhotoPlan.variantCandidates(variant(PINK,
                        lead(inherited(211, "all", 0, 11)), lead(inherited(212, "red", 1, 12)),
                        lead(inherited(213, "pink-stale", 2, 13))), true, gallery));
        assertEquals(List.of(),
                CataloguePhotoPlan.variantCandidates(variant(WHITE,
                        lead(inherited(311, "all", 0, 11))), true, gallery));
        // An own choice is listed once, with its choice flag.
        assertEquals(List.of(
                        new Ref(-602L, "own-chosen", Origin.PRODUCT, true),
                        new Ref(-601L, "own-first", Origin.PRODUCT, false),
                        new Ref(13L, "pink", Origin.GALLERY, false)),
                CataloguePhotoPlan.variantCandidates(variant(PINK,
                        own(601, "own-first", 0), lead(own(602, "own-chosen", 1))), true, gallery));
    }

    @Test
    void theCompactRowTakesOwnAndCataloguePhotosInTheProductsOrder() {
        Variant red = variant(RED,
                inherited(111, "all", 0, 11), own(501, "first", 1), inherited(116, "internal", 2, 16),
                own(504, "first", 3), own(502, "second", 4), own(null, "unsaved", 5));
        Set<Long> catalogueIds = Set.of(11L, 12L);

        assertEquals(List.of(new Ref(11L, "all", Origin.PRODUCT, false)),
                CataloguePhotoPlan.compactRow(red, catalogueIds, 1, ALWAYS));
        assertEquals(List.of(
                        new Ref(11L, "all", Origin.PRODUCT, false),
                        new Ref(-501L, "first", Origin.PRODUCT, false),
                        new Ref(-502L, "second", Origin.PRODUCT, false),
                        new Ref(null, "unsaved", Origin.PRODUCT, false)),
                CataloguePhotoPlan.compactRow(red, catalogueIds, 8, ALWAYS));
        assertEquals(List.of(new Ref(-501L, "first", Origin.PRODUCT, false)),
                CataloguePhotoPlan.compactRow(red, catalogueIds, 1, ref -> !ref.storageKey().equals("all")));
    }

    @Test
    void aReadableChoiceCapsTheCompactRowAndAnUnreadableOneIsIgnored() {
        Variant red = variant(RED,
                own(501, "first", 0), lead(own(502, "chosen-one", 1)), lead(own(503, "chosen-two", 2)),
                lead(inherited(116, "internal", 3, 16)));
        Set<Long> catalogueIds = Set.of(11L);

        assertEquals(List.of(new Ref(-502L, "chosen-one", Origin.PRODUCT, true)),
                CataloguePhotoPlan.compactRow(red, catalogueIds, 1, ALWAYS));
        assertEquals(List.of(
                        new Ref(-502L, "chosen-one", Origin.PRODUCT, true),
                        new Ref(-503L, "chosen-two", Origin.PRODUCT, true)),
                CataloguePhotoPlan.compactRow(red, catalogueIds, 8, ALWAYS));
        assertEquals(List.of(new Ref(-503L, "chosen-two", Origin.PRODUCT, true)),
                CataloguePhotoPlan.compactRow(red, catalogueIds, 8,
                        ref -> !ref.storageKey().equals("chosen-one")));
        // No readable choice left: back to every eligible photo.
        assertEquals(List.of(new Ref(-501L, "first", Origin.PRODUCT, false)),
                CataloguePhotoPlan.compactRow(red, catalogueIds, 8,
                        ref -> !ref.storageKey().startsWith("chosen")));
        assertEquals(List.of(new Ref(16L, "internal", Origin.PRODUCT, true)),
                CataloguePhotoPlan.compactRow(red, Set.of(16L), 8,
                        ref -> !ref.storageKey().startsWith("chosen")));
    }

    /** "signed id, basis, colour"; "all" for a photo of every colour. */
    private static List<String> detail(FamilySelection selection) {
        return selection.detail().stream().map(entry -> entry.ref().signedId() + " " + entry.basis() + " "
                + (entry.productId() == null ? "all" : entry.productId())).toList();
    }

    private static GalleryPhoto gallery(long id, String storageKey, int position, Long colourId) {
        return new GalleryPhoto(id, storageKey, position, colourId);
    }

    private static Variant variant(Long productId, VariantPhoto... photos) {
        return new Variant(productId, List.of(photos));
    }

    private static VariantPhoto own(Long id, String storageKey, int position) {
        return new VariantPhoto(id, storageKey, position, null, false);
    }

    private static VariantPhoto own(long id, String storageKey, int position) {
        return own(Long.valueOf(id), storageKey, position);
    }

    private static VariantPhoto inherited(long id, String storageKey, int position, long seriesPhotoId) {
        return new VariantPhoto(id, storageKey, position, seriesPhotoId, false);
    }

    private static VariantPhoto lead(VariantPhoto photo) {
        return new VariantPhoto(photo.id(), photo.storageKey(), photo.position(), photo.familyPhotoId(), true);
    }
}
