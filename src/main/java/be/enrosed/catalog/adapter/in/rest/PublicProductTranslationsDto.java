package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.shared.Language;

import java.util.List;

/** One optimistic, all-or-nothing editor snapshot for public product copy and gallery metadata.
 * Family fields are empty/null for a valid standalone product. */
public record PublicProductTranslationsDto(
        String revision,
        Long familyId,
        Long productId,
        List<ProductFamilyDto.TextDto> familyTexts,
        List<ProductDto.TextDto> productTexts,
        List<ImageDto> images,
        ProductFamilyDto family,
        ProductDto product,
        ProductPublicCopyDto productPublicCopy
) {
    /** Public product naming is deliberately separate from document translations. */
    public record ProductPublicCopyDto(
            String publicName,
            List<ProductPublicTextDto> texts
    ) {}

    public record ProductPublicTextDto(Language language, String publicName) {}

    public record ImageDto(
            Long imageId,
            int position,
            List<ProductFamilyDto.AltTextDto> altTexts
    ) {}

    /** The complete editable payload. IDs and the revision are deliberate concurrency guards. */
    public record UpdateDto(
            String revision,
            Long familyId,
            List<ProductFamilyDto.TextDto> familyTexts,
            List<ProductDto.TextDto> productTexts,
            List<ImageDto> images,
            ProductPublicCopyDto productPublicCopy
    ) {
        /** Backward-compatible source shape used by the existing dashboard and tests. */
        public UpdateDto(
                String revision,
                Long familyId,
                List<ProductFamilyDto.TextDto> familyTexts,
                List<ProductDto.TextDto> productTexts,
                List<ImageDto> images) {
            this(revision, familyId, familyTexts, productTexts, images, null);
        }
    }
}
