package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductDto;
import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.in.rest.ProductFamilyDtoFactory;
import be.enrosed.catalog.adapter.in.rest.PublicProductTranslationsDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.catalog.domain.ProductText;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns the transaction boundary for the public-copy part of the product editor. */
@ApplicationScoped
public class PublicProductTranslationsService {
    private static final int MAX_DB_SHORT = 255;
    private static final int MAX_SUMMARY = 2_000;
    private static final int MAX_LONG = 10_000;
    private static final int MAX_ALT = 1_000;
    private static final int MAX_HIGHLIGHT = 1_000;
    private static final int MAX_JSON = 10_000;

    private final CatalogDaos.Products products;
    private final CanonicalCatalogDaos.Families families;
    private final ProductFamilyDtoFactory familyDtos;
    private final ProductService productService;
    private final FamilyPhotoCompatibilityService photoCompatibility;
    private final ProductFamilyWriteGuard writeGuard;
    private final ObjectMapper json;

    @Inject
    WebsiteRebuildService websiteRebuild;

    public PublicProductTranslationsService(
            CatalogDaos.Products products,
            CanonicalCatalogDaos.Families families,
            ProductFamilyDtoFactory familyDtos,
            ProductService productService,
            FamilyPhotoCompatibilityService photoCompatibility,
            ProductFamilyWriteGuard writeGuard,
            ObjectMapper json) {
        this.products = products;
        this.families = families;
        this.familyDtos = familyDtos;
        this.productService = productService;
        this.photoCompatibility = photoCompatibility;
        this.writeGuard = writeGuard;
        this.json = json;
    }

    @Transactional
    public PublicProductTranslationsDto get(long productId) {
        Context context = context(productId, false);
        return snapshot(context.product(), context.family());
    }

    @Transactional
    public PublicProductTranslationsDto update(
            long productId, PublicProductTranslationsDto.UpdateDto request) {
        if (request == null) throw new BusinessRuleException("Geen publieke vertalingen meegestuurd");
        Context context = context(productId, true);
        ProductEntity product = context.product();
        ProductFamilyEntity family = context.family();
        if (!Objects.equals(request.familyId(), family == null ? null : family.id)) {
            throw new BusinessRuleException(
                    "Het product is intussen aan een andere productfamilie gekoppeld; herlaad voor je bewaart");
        }
        String actualRevision = revision(product, family);
        if (request.revision() == null || !MessageDigest.isEqual(
                actualRevision.getBytes(StandardCharsets.US_ASCII),
                request.revision().getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessRuleException(
                    "Publieke productinformatie is intussen gewijzigd; herlaad voor je bewaart");
        }

        if (family == null) {
            if (request.familyTexts() != null && !request.familyTexts().isEmpty()) {
                throw new BusinessRuleException(
                        "Een los product kan geen productfamilieteksten bewaren");
            }
            if (request.images() != null && !request.images().isEmpty()) {
                throw new BusinessRuleException(
                        "Een los product kan geen productfamiliefoto's bewaren");
            }
        } else {
            replaceFamilyTexts(family, request.familyTexts());
            replaceImages(family, request.images());
        }
        replaceProductTexts(product, request.productTexts());
        String updatedRevision = revision(product, family);
        boolean publicChange = !Objects.equals(actualRevision, updatedRevision);
        if (publicChange && family != null) family.updatedAt = Instant.now();
        products.flush();
        families.flush();
        if (family != null) {
            writeGuard.validateFamilies(List.of(family.id));
            photoCompatibility.sync(family);
        }
        products.flush();
        families.flush();
        if (publicChange && websiteRebuild != null) websiteRebuild.queue();
        return snapshot(product, family);
    }

    /**
     * Bulk-safe compatibility path for the CSV/Excel translation exchange.
     * Callers provide a complete per-product translation snapshot; all affected families and
     * products are locked in canonical order, validated together, and enqueue one rebuild.
     */
    @Transactional
    public int replaceProductTexts(Map<Long, List<ProductDto.TextDto>> requestedByProduct) {
        if (requestedByProduct == null || requestedByProduct.isEmpty()) return 0;
        List<Long> productIds = requestedByProduct.keySet().stream()
                .filter(Objects::nonNull).sorted().toList();
        if (productIds.size() != requestedByProduct.size()) {
            throw new BusinessRuleException("Elk vertaalproduct moet een geldig product-id hebben");
        }
        Map<Long, Long> observedFamilies = new java.util.LinkedHashMap<>();
        for (Long productId : productIds) {
            ProductEntity product = products.findById(productId);
            if (product == null) throw new NotFoundException("Product", productId);
            observedFamilies.put(productId, product.familyId);
        }
        writeGuard.lockFamilies(observedFamilies.values());
        for (Long productId : productIds) {
            Long lockedFamilyId = writeGuard.lockProduct(productId);
            if (!Objects.equals(observedFamilies.get(productId), lockedFamilyId)) {
                throw new BusinessRuleException(
                        "Een vertaalproduct is intussen naar een andere familie verplaatst; "
                                + "herlaad het bestand");
            }
        }

        Map<Long, ProductFamilyEntity> lockedFamilies = new HashMap<>();
        observedFamilies.values().stream().filter(Objects::nonNull).distinct().forEach(familyId -> {
            ProductFamilyEntity family = families.findById(familyId, LockModeType.PESSIMISTIC_WRITE);
            if (family == null) throw new BusinessRuleException("Onbekende productfamilie " + familyId);
            lockedFamilies.put(familyId, family);
        });
        int changed = 0;
        for (Long productId : productIds) {
            ProductEntity product = products.findById(productId, LockModeType.PESSIMISTIC_WRITE);
            ProductFamilyEntity family = lockedFamilies.get(product.familyId);
            String before = revision(product, family);
            replaceProductTexts(product, requestedByProduct.get(productId));
            if (!Objects.equals(before, revision(product, family))) changed++;
        }
        if (changed == 0) return 0;
        lockedFamilies.values().forEach(family -> family.updatedAt = Instant.now());
        products.flush();
        families.flush();
        writeGuard.validateFamilies(lockedFamilies.keySet());
        products.flush();
        families.flush();
        if (websiteRebuild != null) websiteRebuild.queue();
        return changed;
    }

    /**
     * Applies only the language rows present in a CSV/Excel import. The merge happens after the
     * family and product locks are held, so a concurrent editor save in an absent language can
     * never be overwritten by an older spreadsheet snapshot.
     */
    @Transactional
    public int patchProductTexts(
            Map<Long, Map<Language, ProductDto.TextDto>> requestedByProduct) {
        if (requestedByProduct == null || requestedByProduct.isEmpty()) return 0;
        List<Long> productIds = requestedByProduct.keySet().stream()
                .filter(Objects::nonNull).sorted().toList();
        if (productIds.size() != requestedByProduct.size()) {
            throw new BusinessRuleException("Elk vertaalproduct moet een geldig product-id hebben");
        }
        Map<Long, Long> observedFamilies = new java.util.LinkedHashMap<>();
        for (Long productId : productIds) {
            ProductEntity product = products.findById(productId);
            if (product == null) throw new NotFoundException("Product", productId);
            observedFamilies.put(productId, product.familyId);
        }
        writeGuard.lockFamilies(observedFamilies.values());
        for (Long productId : productIds) {
            Long lockedFamilyId = writeGuard.lockProduct(productId);
            if (!Objects.equals(observedFamilies.get(productId), lockedFamilyId)) {
                throw new BusinessRuleException(
                        "Een vertaalproduct is intussen naar een andere familie verplaatst; "
                                + "herlaad het bestand");
            }
        }

        Map<Long, ProductFamilyEntity> lockedFamilies = new HashMap<>();
        observedFamilies.values().stream().filter(Objects::nonNull).distinct().forEach(familyId -> {
            ProductFamilyEntity family = families.findById(familyId, LockModeType.PESSIMISTIC_WRITE);
            if (family == null) throw new BusinessRuleException("Onbekende productfamilie " + familyId);
            lockedFamilies.put(familyId, family);
        });
        int changed = 0;
        for (Long productId : productIds) {
            ProductEntity product = products.findById(productId, LockModeType.PESSIMISTIC_WRITE);
            ProductFamilyEntity family = lockedFamilies.get(product.familyId);
            String before = revision(product, family);
            Map<Language, ProductDto.TextDto> merged = new java.util.EnumMap<>(Language.class);
            product.texts.forEach(text -> merged.put(text.language, new ProductDto.TextDto(
                    text.language, text.name, text.description, text.colour, text.variantSize)));
            Map<Language, ProductDto.TextDto> patches = requestedByProduct.get(productId);
            if (patches == null) {
                throw new BusinessRuleException("Geen vertaalregels voor product " + productId);
            }
            for (Map.Entry<Language, ProductDto.TextDto> patch : patches.entrySet()) {
                Language language = patch.getKey();
                ProductDto.TextDto input = patch.getValue();
                if (language == null || input == null || input.language() != language) {
                    throw new BusinessRuleException(
                            "Elke vertaalregel moet exact bij haar taal horen");
                }
                ProductDto.TextDto normalized = withoutBaseValues(product, input);
                if (isEmpty(normalized)) merged.remove(language);
                else merged.put(language, normalized);
            }
            replaceProductTexts(product, merged.values().stream()
                    .sorted(Comparator.comparing(ProductDto.TextDto::language)).toList());
            if (!Objects.equals(before, revision(product, family))) changed++;
        }
        if (changed == 0) return 0;
        lockedFamilies.values().forEach(family -> family.updatedAt = Instant.now());
        products.flush();
        families.flush();
        writeGuard.validateFamilies(lockedFamilies.keySet());
        products.flush();
        families.flush();
        if (websiteRebuild != null) websiteRebuild.queue();
        return changed;
    }

    private Context context(long productId, boolean lock) {
        ProductEntity observed = products.findById(productId);
        if (observed == null) throw new NotFoundException("Product", productId);
        Long observedFamilyId = observed.familyId;
        if (lock) {
            if (observedFamilyId != null) writeGuard.lockFamilies(List.of(observedFamilyId));
            Long lockedFamilyId = writeGuard.lockProduct(productId);
            if (!Objects.equals(observedFamilyId, lockedFamilyId)) {
                throw new BusinessRuleException(
                        "Het product is intussen aan een andere productfamilie gekoppeld; herlaad voor je bewaart");
            }
        }
        ProductEntity product = products.findById(productId,
                lock ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE);
        ProductFamilyEntity family = observedFamilyId == null ? null : families.findById(
                observedFamilyId, lock ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE);
        if (observedFamilyId != null
                && (family == null || !Objects.equals(product.familyId, family.id))) {
            throw new BusinessRuleException("De productfamiliekoppeling is niet meer geldig");
        }
        return new Context(product, family);
    }

    private PublicProductTranslationsDto snapshot(
            ProductEntity product, ProductFamilyEntity family) {
        List<ProductFamilyDto.TextDto> familyTexts = family == null ? List.of() : family.texts.stream()
                .sorted(Comparator.comparing(text -> text.language))
                .map(text -> new ProductFamilyDto.TextDto(
                        text.language, text.name, text.summary, text.description, text.format,
                        readStrings(text.highlightsJson), text.seoTitle, text.seoDescription))
                .toList();
        List<ProductDto.TextDto> productTexts = product.texts.stream()
                .sorted(Comparator.comparing(text -> text.language))
                .map(text -> new ProductDto.TextDto(
                        text.language, text.name, text.description, text.colour,
                        text.variantSize))
                .toList();
        List<PublicProductTranslationsDto.ImageDto> images = family == null ? List.of() : family.photos.stream()
                .sorted(Comparator.comparingInt((ProductFamilyPhotoEntity image) -> image.position)
                        .thenComparing(image -> image.id))
                .map(image -> new PublicProductTranslationsDto.ImageDto(
                        image.id, image.position, readAltTexts(image.altTextsJson)))
                .toList();
        return new PublicProductTranslationsDto(
                revision(product, family), family == null ? null : family.id, product.id,
                familyTexts, productTexts, images,
                family == null ? null : familyDtos.from(family),
                ProductDto.from(productService.get(product.id)));
    }

    private void replaceFamilyTexts(
            ProductFamilyEntity family, List<ProductFamilyDto.TextDto> requested) {
        List<ProductFamilyDto.TextDto> inputs = requested == null ? List.of() : requested;
        Set<Language> seen = EnumSet.noneOf(Language.class);
        Map<Language, ProductFamilyDto.TextDto> replacements = new java.util.EnumMap<>(Language.class);
        for (ProductFamilyDto.TextDto input : inputs) {
            if (input == null || input.language() == null || !seen.add(input.language())) {
                throw new BusinessRuleException("Elke familietaal mag exact één keer voorkomen");
            }
            /* Normalize and validate before mutating managed rows. */
            List<String> highlights = validHighlights(input.highlights());
            writeBounded(highlights, MAX_JSON, "Familie-highlights");
            replacements.put(input.language(), new ProductFamilyDto.TextDto(
                    input.language(), optional(input.name(), MAX_DB_SHORT),
                    optional(input.summary(), MAX_SUMMARY), optional(input.description(), MAX_LONG),
                    optional(input.format(), MAX_DB_SHORT), highlights,
                    optional(input.seoTitle(), MAX_DB_SHORT),
                    optional(input.seoDescription(), MAX_SUMMARY)));
        }
        family.texts.removeIf(existing -> !replacements.containsKey(existing.language));
        for (ProductFamilyTextEntity existing : family.texts) {
            ProductFamilyDto.TextDto input = replacements.remove(existing.language);
            apply(existing, input);
        }
        for (ProductFamilyDto.TextDto input : replacements.values()) {
            ProductFamilyTextEntity added = new ProductFamilyTextEntity();
            added.family = family;
            added.language = input.language();
            apply(added, input);
            family.texts.add(added);
        }
    }

    private void replaceProductTexts(ProductEntity product, List<ProductDto.TextDto> requested) {
        List<ProductDto.TextDto> inputs = requested == null ? List.of() : requested;
        Set<Language> seen = EnumSet.noneOf(Language.class);
        Map<Language, ProductText> replacements = new java.util.EnumMap<>(Language.class);
        for (ProductDto.TextDto input : inputs) {
            if (input == null || input.language() == null || !seen.add(input.language())) {
                throw new BusinessRuleException("Elke producttaal mag exact één keer voorkomen");
            }
            ProductText value = new ProductText(input.language(),
                    optional(input.name(), MAX_DB_SHORT), optional(input.description(), MAX_SUMMARY),
                    optional(input.colour(), MAX_DB_SHORT),
                    optional(input.variantSize(), MAX_DB_SHORT));
            if (value.isEmpty()) continue;
            replacements.put(value.language(), value);
        }
        product.texts.removeIf(existing -> !replacements.containsKey(existing.language));
        for (ProductTextEntity existing : product.texts) {
            ProductText value = replacements.remove(existing.language);
            apply(existing, value);
        }
        for (ProductText value : replacements.values()) {
            ProductTextEntity added = new ProductTextEntity();
            added.product = product;
            added.language = value.language();
            apply(added, value);
            product.texts.add(added);
        }
    }

    private ProductDto.TextDto withoutBaseValues(
            ProductEntity product, ProductDto.TextDto input) {
        return new ProductDto.TextDto(input.language(),
                sameAs(input.name(), product.name) ? null : optional(input.name(), MAX_DB_SHORT),
                sameAs(input.description(), product.description)
                        ? null : optional(input.description(), MAX_SUMMARY),
                sameAs(input.colour(), product.colour)
                        ? null : optional(input.colour(), MAX_DB_SHORT),
                sameAs(input.variantSize(), product.variantSize)
                        ? null : optional(input.variantSize(), MAX_DB_SHORT));
    }

    private static boolean isEmpty(ProductDto.TextDto value) {
        return value.name() == null && value.description() == null
                && value.colour() == null && value.variantSize() == null;
    }

    private static boolean sameAs(String value, String base) {
        return value != null && base != null && value.trim().equalsIgnoreCase(base.trim());
    }

    private void apply(ProductFamilyTextEntity target, ProductFamilyDto.TextDto input) {
        target.name = input.name();
        target.summary = input.summary();
        target.description = input.description();
        target.format = input.format();
        target.highlightsJson = writeBounded(input.highlights(), MAX_JSON, "Familie-highlights");
        target.seoTitle = input.seoTitle();
        target.seoDescription = input.seoDescription();
    }

    private static void apply(ProductTextEntity target, ProductText input) {
        target.name = input.name();
        target.description = input.description();
        target.colour = input.colour();
        target.variantSize = input.variantSize();
    }

    private void replaceImages(
            ProductFamilyEntity family, List<PublicProductTranslationsDto.ImageDto> requested) {
        List<PublicProductTranslationsDto.ImageDto> inputs = requested == null
                ? List.of() : requested;
        Map<Long, PublicProductTranslationsDto.ImageDto> byId = new HashMap<>();
        Map<Long, String> altJsonById = new HashMap<>();
        boolean[] positions = new boolean[family.photos.size()];
        for (PublicProductTranslationsDto.ImageDto input : inputs) {
            if (input == null || input.imageId() == null || byId.put(input.imageId(), input) != null) {
                throw new BusinessRuleException("Elke familiefoto mag exact één keer voorkomen");
            }
            if (input.position() < 0 || input.position() >= positions.length
                    || positions[input.position()]) {
                throw new BusinessRuleException("Fotoposities moeten uniek en aaneensluitend zijn");
            }
            positions[input.position()] = true;
            altJsonById.put(input.imageId(), writeBounded(
                    validAltTexts(input.altTexts()), MAX_JSON, "Foto-alt-teksten"));
        }
        Set<Long> actualIds = family.photos.stream().map(image -> image.id)
                .collect(java.util.stream.Collectors.toSet());
        if (inputs.size() != family.photos.size() || !actualIds.equals(byId.keySet())) {
            throw new BusinessRuleException(
                    "De fotolijst is intussen gewijzigd; herlaad voor je bewaart");
        }
        for (ProductFamilyPhotoEntity image : family.photos) {
            PublicProductTranslationsDto.ImageDto input = byId.get(image.id);
            image.position = input.position();
            image.altTextsJson = altJsonById.get(input.imageId());
            image.altTextSource = "DASHBOARD";
        }
        family.photos.sort(Comparator.comparingInt(image -> image.position));
    }

    private List<ProductFamilyDto.AltTextDto> validAltTexts(
            List<ProductFamilyDto.AltTextDto> requested) {
        Set<Language> seen = EnumSet.noneOf(Language.class);
        List<ProductFamilyDto.AltTextDto> result = new ArrayList<>();
        for (ProductFamilyDto.AltTextDto input : requested == null
                ? List.<ProductFamilyDto.AltTextDto>of() : requested) {
            if (input == null || input.language() == null || !seen.add(input.language())) {
                throw new BusinessRuleException("Elke alt-teksttaal mag exact één keer voorkomen");
            }
            String alt = optional(input.alt(), MAX_ALT);
            if (alt != null) result.add(new ProductFamilyDto.AltTextDto(input.language(), alt));
        }
        return result;
    }

    private String revision(ProductEntity product, ProductFamilyEntity family) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, product.id);
        append(canonical, product.familyId);
        append(canonical, family == null ? null : family.id);
        if (family != null) {
            family.texts.stream().sorted(Comparator.comparing(text -> text.language)).forEach(text -> {
                append(canonical, text.language);
                append(canonical, text.name);
                append(canonical, text.summary);
                append(canonical, text.description);
                append(canonical, text.format);
                append(canonical, normalizedJson(text.highlightsJson));
                append(canonical, text.seoTitle);
                append(canonical, text.seoDescription);
            });
        }
        product.texts.stream().sorted(Comparator.comparing(text -> text.language)).forEach(text -> {
            append(canonical, text.language);
            append(canonical, text.name);
            append(canonical, text.description);
            append(canonical, text.colour);
            append(canonical, text.variantSize);
        });
        if (family != null) {
            family.photos.stream()
                    .sorted(Comparator.comparingInt((ProductFamilyPhotoEntity image) -> image.position)
                            .thenComparing(image -> image.id))
                    .forEach(image -> {
                        append(canonical, image.id);
                        append(canonical, image.position);
                        readAltTexts(image.altTextsJson).stream()
                                .sorted(Comparator.comparing(ProductFamilyDto.AltTextDto::language))
                                .forEach(alt -> {
                                    append(canonical, alt.language());
                                    append(canonical, alt.alt());
                                });
                    });
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is niet beschikbaar", exception);
        }
    }

    private String normalizedJson(String value) {
        return write(readStrings(value));
    }

    private static void append(StringBuilder target, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('|');
    }

    private List<String> readStrings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException exception) {
            throw new BusinessRuleException("Ongeldige familie-highlights in de database");
        }
    }

    private List<ProductFamilyDto.AltTextDto> readAltTexts(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value,
                    new TypeReference<List<ProductFamilyDto.AltTextDto>>() {});
        } catch (JsonProcessingException exception) {
            throw new BusinessRuleException("Ongeldige alt-teksten in de database");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessRuleException("Kan publieke productinformatie niet serialiseren");
        }
    }

    private String writeBounded(Object value, int maxLength, String label) {
        String encoded = write(value);
        if (encoded.length() > maxLength) {
            throw new BusinessRuleException(label + " zijn samen langer dan "
                    + maxLength + " tekens");
        }
        return encoded;
    }

    private static List<String> validHighlights(List<String> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : requested) {
            String normalized = optional(value, MAX_HIGHLIGHT);
            if (normalized == null) {
                throw new BusinessRuleException("Familie-highlights mogen niet leeg zijn");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.length() > maxLength) {
            throw new BusinessRuleException(
                    "Publieke producttekst is langer dan " + maxLength + " tekens");
        }
        return result;
    }

    private record Context(ProductEntity product, ProductFamilyEntity family) {}
}
