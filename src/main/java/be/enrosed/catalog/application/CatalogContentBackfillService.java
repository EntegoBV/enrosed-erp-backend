package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogLocalizationBackfillEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyPhotoEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.VariantSizes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Inserts missing canonical translations and corrects only exact known stale import values. */
@ApplicationScoped
public class CatalogContentBackfillService {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(CatalogContentBackfillService.class);
    private static final String RESOURCE = "/i18n/catalog-content-backfill.json";
    private static final String FAMILY_COPY_RESOURCE = "/i18n/catalog-family-copy.json";
    private static final Pattern DUTCH_IN_EN = Pattern.compile(
            "(?i)\\b(roos|rozen|gepreserveerde|geconserveerde|stolp|spiegeldoos|vensterdoos)\\b");

    private final CanonicalCatalogDaos.Families families;
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final CanonicalCatalogDaos.LocalizationBackfills markers;
    private final ObjectMapper json;
    private final ProductFamilyWriteGuard writeGuard;
    private final CatalogMutationLock mutationLock;

    @Inject
    public CatalogContentBackfillService(
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            CanonicalCatalogDaos.LocalizationBackfills markers,
            ObjectMapper json,
            ProductFamilyWriteGuard writeGuard,
            CatalogMutationLock mutationLock) {
        this.families = families;
        this.products = products;
        this.categories = categories;
        this.markers = markers;
        this.json = json;
        this.writeGuard = writeGuard;
        this.mutationLock = mutationLock;
    }

    /** Pure resource-test compatibility. */
    CatalogContentBackfillService(
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            CanonicalCatalogDaos.LocalizationBackfills markers,
            ObjectMapper json) {
        this(families, products, categories, markers, json, null, null);
    }

    @Transactional
    public Result apply() {
        Bundle bundle = bundle();
        validate(bundle);
        if (mutationLock != null) mutationLock.acquire();
        CatalogLocalizationBackfillEntity marker = markers.findById(bundle.version());
        if (marker != null && !Objects.equals(marker.payloadSha256, bundle.sha256())) {
            throw new IllegalStateException("Catalogusvertaling-versie " + bundle.version()
                    + " bestaat al met een andere inhoud; geef de nieuwe payload een nieuwe versie");
        }
        lockTargets(bundle);
        /* Exact known-stale values are safe to correct on every replacement import; dashboard
           edits differ from those source literals and are therefore still preserved. */
        boolean correctKnownStale = true;
        Counter counter = new Counter();

        Map<String, CategoryEntity> categoriesByKey = new HashMap<>();
        categories.listAll().forEach(category -> categoriesByKey.put(
                CategoryPublicKey.from(category.code), category));
        int matchedCategories = 0;
        for (Map.Entry<String, Map<Language, CategoryCopy>> entry
                : bundle.categories().entrySet()) {
            CategoryEntity category = categoriesByKey.get(CategoryPublicKey.from(entry.getKey()));
            if (category == null) continue;
            String beforeCategory = categoryFingerprint(category);
            matchedCategories++;
            for (Map.Entry<Language, CategoryCopy> localized : entry.getValue().entrySet()) {
                CategoryTextEntity text = category.texts.stream()
                        .filter(item -> item.language == localized.getKey()).findFirst().orElse(null);
                if (text == null) {
                    text = new CategoryTextEntity();
                    text.category = category;
                    text.language = localized.getKey();
                    category.texts.add(text);
                    counter.inserted++;
                }
                CategoryCopy value = localized.getValue();
                text.name = merge(text.name, value.name(), known(category.name, localized.getKey()),
                        correctKnownStale, counter);
                text.description = merge(text.description, value.description(),
                        known(category.description, localized.getKey()), correctKnownStale, counter);
                text.eyebrow = merge(text.eyebrow, value.eyebrow(),
                        known(category.eyebrow, localized.getKey()), correctKnownStale, counter);
                text.mobileName = merge(text.mobileName, value.mobileName(),
                        knownCategoryMobileName(entry.getKey(), localized.getKey(),
                                text.mobileName, category.mobileName),
                        correctKnownStale, counter);
                text.navigationName = merge(text.navigationName, value.navigationName(),
                        known(category.navigationName, localized.getKey()), correctKnownStale,
                        counter);
                text.footerName = merge(text.footerName, value.footerName(),
                        known(category.footerName, localized.getKey()), correctKnownStale,
                        counter);
            }
            if (!Objects.equals(beforeCategory, categoryFingerprint(category))) {
                categories.getEntityManager().lock(
                        category, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            }
        }

        int matchedFamilies = 0;
        int matchedVariants = 0;
        int matchedImages = 0;
        for (Map.Entry<String, FamilySeed> entry : bundle.families().entrySet()) {
            ProductFamilyEntity family = families.find("familyKey", entry.getKey()).firstResult();
            if (family == null) continue;
            matchedFamilies++;
            FamilySeed seed = entry.getValue();
            Map<Language, String> profile = requiredProfile(bundle, seed.profile());
            Map<Language, String> formatProfile = requiredFormatProfile(bundle, seed.profile());
            Map<Language, List<String>> highlightProfile = requiredHighlightProfile(
                    bundle, seed.profile());
            Map<Language, String> localizedNames = seed.names();
            for (Language language : Language.values()) {
                FamilyCopy approved = bundle.familyCopy().getOrDefault(
                        entry.getKey(), Map.of()).get(language);
                String summaryCandidate = approved == null
                        ? profile.get(language) : approved.summary();
                String descriptionCandidate = approved == null
                        ? profile.get(language) : approved.description();
                String formatCandidate = approved == null
                        ? formatProfile.get(language) : approved.format();
                List<String> highlightCandidates = approved == null
                        ? highlightProfile.get(language) : approved.highlights();
                ProductFamilyTextEntity text = family.texts.stream()
                        .filter(item -> item.language == language).findFirst().orElse(null);
                if (text == null) {
                    text = new ProductFamilyTextEntity();
                    text.family = family;
                    text.language = language;
                    text.highlightsJson = "[]";
                    family.texts.add(text);
                    counter.inserted++;
                }
                String name = localizedNames.get(language);
                text.name = merge(text.name, name, knownFamilyName(
                                entry.getKey(), family.name, language, text.name),
                        correctKnownStale, counter);
                text.summary = merge(text.summary, summaryCandidate,
                        knownFamilyCopy(entry.getKey(), "summary", text.summary,
                                summaryCandidate, language),
                        correctKnownStale, counter);
                text.seoTitle = merge(text.seoTitle, name + " | Enrosed Wholesale",
                        null, false, counter);
                text.seoDescription = merge(text.seoDescription, summaryCandidate,
                        null, false, counter);
                String knownDescription = approved != null
                        && Objects.equals(normalize(text.description),
                                normalize(approved.knownStaleDescription()))
                        ? text.description : knownDutchStale(text.description, language);
                text.description = merge(text.description, descriptionCandidate,
                        knownDescription != null ? knownDescription : knownFamilyCopy(
                                entry.getKey(), "description", text.description,
                                descriptionCandidate, language),
                        correctKnownStale, counter);
                text.format = merge(text.format, formatCandidate,
                        knownFamilyCopy(entry.getKey(), "format", text.format,
                                formatCandidate, language),
                        correctKnownStale, counter);
                String highlightCandidate = write(highlightCandidates);
                String highlightStale = knownDutchStale(text.highlightsJson, language);
                if (text.highlightsJson == null || text.highlightsJson.isBlank()
                        || "[]".equals(text.highlightsJson.strip())) {
                    text.highlightsJson = highlightCandidate;
                } else if (correctKnownStale && highlightStale != null
                        && !text.highlightsJson.equals(highlightCandidate)) {
                    text.highlightsJson = highlightCandidate;
                    counter.corrected++;
                }
            }

            List<ProductEntity> members = products.list(
                    "familyId = ?1 order by variantPosition, id", family.id);
            for (ProductEntity product : members) {
                if (product.canonicalVariantKey == null || product.canonicalVariantKey.isBlank()) continue;
                if (!bundle.targetVariantKeys().contains(product.canonicalVariantKey)) continue;
                matchedVariants++;
                if (product.publicName == null || product.publicName.isBlank()) {
                    product.publicName = product.name;
                }
                String colourHex = product.colour == null
                        ? null : bundle.colourHexes().get(product.colour);
                if ((product.colourHex == null || product.colourHex.isBlank())
                        && colourHex != null) {
                    product.colourHex = colourHex;
                }
                for (Language language : Language.values()) {
                    String color = localizedColor(bundle, product.colour, language);
                    String size = localizedSize(product.variantSize, language);
                    Map<Language, String> namedVariant = bundle.variantNames()
                            .get(product.canonicalVariantKey);
                    String name = namedVariant != null ? namedVariant.get(language)
                            : color == null ? localizedNames.get(language) : color;
                    ProductTextEntity text = product.texts.stream()
                            .filter(item -> item.language == language).findFirst().orElse(null);
                    if (text == null) {
                        text = new ProductTextEntity();
                        text.product = product;
                        text.language = language;
                        product.texts.add(text);
                        counter.inserted++;
                    }
                    text.publicName = merge(text.publicName, name, known(product.publicName, language),
                            correctKnownStale, counter);
                    text.description = merge(text.description, profile.get(language),
                            knownDutchStale(text.description, language),
                            correctKnownStale, counter);
                    if (color != null) {
                        text.colour = merge(text.colour, color, known(product.colour, language),
                                correctKnownStale, counter);
                    }
                    if (size != null) {
                        text.variantSize = merge(text.variantSize, size,
                                known(product.variantSize, language), correctKnownStale, counter);
                    }
                }
            }

            for (ProductFamilyPhotoEntity image : family.photos) {
                if (!bundle.targetImageKeys().contains(
                        family.familyKey + ":" + image.sourceKey)) continue;
                matchedImages++;
                List<ProductFamilyDto.AltTextDto> existing = readAlts(image.altTextsJson);
                Map<Language, String> values = new EnumMap<>(Language.class);
                existing.forEach(alt -> values.put(alt.language(), alt.alt()));
                String rawColor = image.variantProduct == null
                        ? image.variantColor : image.variantProduct.colour;
                for (Language language : Language.values()) {
                    String familyName = localizedNames.get(language);
                    String color = localizedColor(bundle, rawColor, language);
                    String candidate = color == null ? familyName
                            : bundle.altPatterns().get(language)
                                    .replace("{family}", familyName).replace("{color}", color);
                    String stale = language == Language.EN && image.altTextSource != null
                            && image.altTextSource.startsWith("WEBSITE")
                            ? staleAlt(family, rawColor) : null;
                    String merged = merge(values.get(language), candidate, stale,
                            correctKnownStale, counter);
                    values.put(language, merged);
                }
                image.altTextsJson = write(values.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(value -> new ProductFamilyDto.AltTextDto(
                                value.getKey(), value.getValue())).toList());
            }
        }

        boolean completeTarget = matchedCategories == bundle.expectedCategories()
                && matchedFamilies == bundle.expectedFamilies()
                && matchedVariants == bundle.expectedVariants()
                && matchedImages == bundle.expectedImages();
        if (completeTarget) {
            if (marker == null) {
                marker = new CatalogLocalizationBackfillEntity();
                marker.version = bundle.version();
                markers.persist(marker);
            }
            if (marker.payloadSha256 == null) marker.payloadSha256 = bundle.sha256();
            marker.appliedAt = Instant.now();
            marker.insertedRows = counter.inserted;
            marker.correctedKnownFields = counter.corrected;
            markers.flush();
        }
        return new Result(bundle.version(), bundle.sha256(), matchedCategories, matchedFamilies,
                matchedVariants, matchedImages, counter.inserted, counter.corrected);
    }

    /** Locks the same aggregates as the editor, in its global family -> product -> category order. */
    private void lockTargets(Bundle bundle) {
        if (writeGuard == null || families == null || products == null || categories == null) return;
        List<ProductFamilyEntity> targetFamilies = families.listAll().stream()
                .filter(family -> bundle.families().containsKey(family.familyKey))
                .filter(family -> family.id != null)
                .sorted(Comparator.comparing(family -> family.id)).toList();
        writeGuard.lockFamilies(targetFamilies.stream().map(family -> family.id).toList());
        targetFamilies.forEach(family -> families.getEntityManager().refresh(
                family, LockModeType.PESSIMISTIC_WRITE));

        List<ProductEntity> targetProducts = products.listAll().stream()
                .filter(product -> product.id != null && product.canonicalVariantKey != null)
                .filter(product -> bundle.targetVariantKeys().contains(product.canonicalVariantKey))
                .sorted(Comparator.comparing(product -> product.id)).toList();
        writeGuard.lockProducts(targetProducts.stream().map(product -> product.id).toList());

        Set<String> categoryKeys = bundle.categories().keySet().stream()
                .map(CategoryPublicKey::from).collect(java.util.stream.Collectors.toSet());
        categories.listAll().stream()
                .filter(category -> category.id != null
                        && categoryKeys.contains(CategoryPublicKey.from(category.code)))
                .sorted(Comparator.comparing(category -> category.id))
                .forEach(category -> {
                    CategoryEntity locked = categories.findById(
                            category.id, LockModeType.PESSIMISTIC_WRITE);
                    categories.getEntityManager().refresh(locked, LockModeType.PESSIMISTIC_WRITE);
                });
    }

    private static String categoryFingerprint(CategoryEntity category) {
        StringBuilder value = new StringBuilder();
        category.texts.stream().sorted(Comparator.comparing(text -> text.language)).forEach(text ->
                value.append(text.language).append('\u0000').append(text.name).append('\u0000')
                        .append(text.description).append('\u0000').append(text.eyebrow).append('\u0000')
                        .append(text.mobileName).append('\u0000').append(text.navigationName)
                        .append('\u0000').append(text.footerName).append('\u0001'));
        return value.toString();
    }

    /** Package-visible deterministic resource check used without starting Quarkus or touching DB. */
    void validateResources() {
        validate(bundle());
    }

    private Bundle bundle() {
        try (InputStream input = CatalogContentBackfillService.class.getResourceAsStream(RESOURCE);
             InputStream familyInput = CatalogContentBackfillService.class
                     .getResourceAsStream(FAMILY_COPY_RESOURCE)) {
            if (input == null || familyInput == null) {
                throw new IllegalStateException("Catalogusvertalingen ontbreken");
            }
            byte[] bytes = input.readAllBytes();
            byte[] familyBytes = familyInput.readAllBytes();
            JsonNode root = json.readTree(bytes);
            JsonNode familyRoot = json.readTree(familyBytes);
            String version = required(root.path("version").asText(), "version") + "+"
                    + required(familyRoot.path("version").asText(), "familyCopy.version");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            digest.update((byte) 0);
            String checksum = HexFormat.of().formatHex(digest.digest(familyBytes));
            Map<String, Map<Language, String>> profiles = new LinkedHashMap<>();
            root.path("familyProfiles").fields().forEachRemaining(profile -> {
                EnumMap<Language, String> values = new EnumMap<>(Language.class);
                for (Language language : Language.values()) {
                    values.put(language, profile.getValue().path(language.name()).asText());
                }
                profiles.put(profile.getKey(), Map.copyOf(values));
            });
            Map<String, Map<Language, String>> formatProfiles = new LinkedHashMap<>();
            root.path("formatProfiles").fields().forEachRemaining(profile -> {
                EnumMap<Language, String> values = new EnumMap<>(Language.class);
                for (Language language : Language.values()) {
                    values.put(language, profile.getValue().path(language.name()).asText());
                }
                formatProfiles.put(profile.getKey(), Map.copyOf(values));
            });
            Map<String, Map<Language, List<String>>> highlightProfiles = new LinkedHashMap<>();
            root.path("highlightProfiles").fields().forEachRemaining(profile -> {
                EnumMap<Language, List<String>> values = new EnumMap<>(Language.class);
                for (Language language : Language.values()) {
                    List<String> highlights = new ArrayList<>();
                    profile.getValue().path(language.name()).forEach(item ->
                            highlights.add(item.asText()));
                    values.put(language, List.copyOf(highlights));
                }
                highlightProfiles.put(profile.getKey(), Map.copyOf(values));
            });
            Map<String, Map<Language, CategoryCopy>> categorySeeds = new LinkedHashMap<>();
            root.path("categories").fields().forEachRemaining(category -> {
                EnumMap<Language, CategoryCopy> values = new EnumMap<>(Language.class);
                for (Language language : Language.values()) {
                    JsonNode node = category.getValue().path(language.name());
                    values.put(language, new CategoryCopy(node.path("name").asText(),
                            node.path("description").asText(), node.path("eyebrow").asText(),
                            node.path("mobileName").asText(),
                            node.path("navigationName").asText(),
                            node.path("footerName").asText()));
                }
                categorySeeds.put(category.getKey(), Map.copyOf(values));
            });
            Map<String, FamilySeed> familySeeds = new LinkedHashMap<>();
            root.path("families").fields().forEachRemaining(family -> {
                EnumMap<Language, String> names = new EnumMap<>(Language.class);
                for (Language language : Language.values()) {
                    names.put(language, family.getValue().path(language.name()).asText());
                }
                familySeeds.put(family.getKey(), new FamilySeed(
                        family.getValue().path("profile").asText(), Map.copyOf(names)));
            });
            Map<String, Map<Language, FamilyCopy>> familyCopy = new LinkedHashMap<>();
            familyRoot.path("families").fields().forEachRemaining(family -> {
                EnumMap<Language, FamilyCopy> texts = new EnumMap<>(Language.class);
                for (Language language : Language.values()) {
                    JsonNode node = family.getValue().path(language.name());
                    String summary = node.path("summary").asText(null);
                    String description = node.path("description").asText(null);
                    List<String> highlights = new ArrayList<>();
                    node.path("highlights").forEach(item -> highlights.add(item.asText()));
                    texts.put(language, new FamilyCopy(
                            summary, description,
                            node.path("knownStaleDescription").asText(null),
                            node.path("format").asText(null), List.copyOf(highlights)));
                }
                familyCopy.put(family.getKey(), Map.copyOf(texts));
            });
            Map<String, Map<Language, String>> variantNames = new LinkedHashMap<>();
            root.path("variantNames").fields().forEachRemaining(variant -> {
                EnumMap<Language, String> names = new EnumMap<>(Language.class);
                for (Language language : Language.values()) {
                    names.put(language, variant.getValue().path(language.name()).asText());
                }
                variantNames.put(variant.getKey(), Map.copyOf(names));
            });
            Set<String> targetVariantKeys = new LinkedHashSet<>();
            root.path("targetVariantKeys").forEach(item -> targetVariantKeys.add(item.asText()));
            Set<String> targetImageKeys = new LinkedHashSet<>();
            root.path("targetImageKeys").forEach(item -> targetImageKeys.add(item.asText()));
            Map<String, Map<Language, String>> colors = new LinkedHashMap<>();
            root.path("colors").fields().forEachRemaining(color -> {
                EnumMap<Language, String> values = new EnumMap<>(Language.class);
                for (Language language : Language.values()) {
                    values.put(language, color.getValue().path(language.name()).asText());
                }
                colors.put(color.getKey(), Map.copyOf(values));
            });
            Map<String, String> colourHexes = new LinkedHashMap<>();
            root.path("colourHexes").fields().forEachRemaining(color ->
                    colourHexes.put(color.getKey(), color.getValue().asText()));
            EnumMap<Language, String> patterns = new EnumMap<>(Language.class);
            for (Language language : Language.values()) {
                patterns.put(language, root.path("imageAltPatterns").path(language.name()).asText());
            }
            JsonNode counts = root.path("expectedCounts");
            return new Bundle(version, checksum, profiles, formatProfiles, highlightProfiles,
                    categorySeeds, familySeeds, familyCopy, variantNames, colors,
                    Set.copyOf(targetVariantKeys), Set.copyOf(targetImageKeys),
                    Map.copyOf(colourHexes), Map.copyOf(patterns),
                    counts.path("categories").asInt(),
                    counts.path("families").asInt(), counts.path("variants").asInt(),
                    counts.path("images").asInt());
        } catch (Exception exception) {
            throw new IllegalStateException("Catalogusvertalingen konden niet gelezen worden", exception);
        }
    }

    private static void validate(Bundle bundle) {
        if (bundle.categories().size() != bundle.expectedCategories()
                || bundle.families().size() != bundle.expectedFamilies()
                || bundle.targetVariantKeys().size() != bundle.expectedVariants()
                || bundle.targetImageKeys().size() != bundle.expectedImages()
                || !bundle.targetVariantKeys().containsAll(bundle.variantNames().keySet())) {
            throw new IllegalStateException("Catalogusvertalingen hebben onjuiste targetaantallen");
        }
        if (bundle.familyCopy().size() != 19
                || !bundle.families().keySet().containsAll(bundle.familyCopy().keySet())) {
            throw new IllegalStateException(
                    "Productspecifieke cataloguscopy moet exact 19 publieke families bevatten");
        }
        bundle.categories().forEach((categoryKey, translations) -> {
            for (Language language : Language.values()) {
                CategoryCopy value = translations.get(language);
                if (value == null) {
                    throw new IllegalStateException("Categoriecopy ontbreekt voor " + categoryKey
                            + "." + language.code());
                }
                required(value.name(), categoryKey + ".name." + language.code());
                required(value.description(), categoryKey + ".description." + language.code());
                required(value.eyebrow(), categoryKey + ".eyebrow." + language.code());
                required(value.mobileName(), categoryKey + ".mobileName." + language.code());
                required(value.navigationName(), categoryKey + ".navigationName."
                        + language.code());
                required(value.footerName(), categoryKey + ".footerName."
                        + language.code());
            }
        });
        EnumMap<Language, java.util.Set<String>> distinctSummaries = new EnumMap<>(Language.class);
        EnumMap<Language, java.util.Set<String>> distinctDescriptions = new EnumMap<>(Language.class);
        for (Language language : Language.values()) {
            distinctSummaries.put(language, new java.util.HashSet<>());
            distinctDescriptions.put(language, new java.util.HashSet<>());
        }
        bundle.familyCopy().forEach((familyKey, translations) -> {
            FamilyCopy english = translations.get(Language.EN);
            if (english == null) {
                throw new IllegalStateException("Engelse family copy ontbreekt voor " + familyKey);
            }
            for (Language language : Language.values()) {
                FamilyCopy value = translations.get(language);
                if (value == null) {
                    throw new IllegalStateException("Family copy ontbreekt voor " + familyKey
                            + "." + language.code());
                }
                String summary = required(value.summary(), familyKey + ".summary."
                        + language.code());
                String description = required(value.description(), familyKey + ".description."
                        + language.code());
                required(value.format(), familyKey + ".format." + language.code());
                if (value.highlights() == null || value.highlights().size() != 3
                        || value.highlights().stream().anyMatch(item -> item == null || item.isBlank())) {
                    throw new IllegalStateException("Family highlights moeten exact drie waarden bevatten: "
                            + familyKey + "." + language.code());
                }
                distinctSummaries.get(language).add(summary);
                distinctDescriptions.get(language).add(description);
                requireSameNumbers(english.summary(), value.summary(),
                        familyKey + ".summary." + language.code());
                requireSameNumbers(english.description(), value.description(),
                        familyKey + ".description." + language.code());
                requireSameNumbers(english.format(), value.format(),
                        familyKey + ".format." + language.code());
                for (int index = 0; index < english.highlights().size(); index++) {
                    requireSameNumbers(english.highlights().get(index), value.highlights().get(index),
                            familyKey + ".highlights[" + index + "]." + language.code());
                }
                requireBrandParity(english.summary(), value.summary(), familyKey, language);
                requireBrandParity(english.description(), value.description(), familyKey, language);
                requireBrandParity(english.format(), value.format(), familyKey, language);
                if (language == Language.EN && DUTCH_IN_EN.matcher(summary).find()) {
                    throw new IllegalStateException("Engelse productsamenvatting bevat Nederlands: "
                            + familyKey);
                }
                if (language == Language.EN && DUTCH_IN_EN.matcher(description).find()) {
                    throw new IllegalStateException("Engelse productbeschrijving bevat Nederlands: "
                            + familyKey);
                }
            }
        });
        distinctSummaries.forEach((language, values) -> {
            if (values.size() != bundle.familyCopy().size()) {
                throw new IllegalStateException("Productspecifieke samenvattingen zijn niet uniek in "
                        + language.code());
            }
        });
        distinctDescriptions.forEach((language, values) -> {
            if (values.size() != bundle.familyCopy().size()) {
                throw new IllegalStateException("Productspecifieke beschrijvingen zijn niet uniek in "
                        + language.code());
            }
        });
        for (Map.Entry<String, FamilySeed> family : bundle.families().entrySet()) {
            if (!bundle.profiles().containsKey(family.getValue().profile())) {
                throw new IllegalStateException("Onbekend copyprofiel voor " + family.getKey());
            }
            if (!bundle.formatProfiles().containsKey(family.getValue().profile())
                    || !bundle.highlightProfiles().containsKey(family.getValue().profile())) {
                throw new IllegalStateException("Onvolledig copyprofiel voor " + family.getKey());
            }
            for (Language language : Language.values()) {
                String name = required(family.getValue().names().get(language),
                        family.getKey() + "." + language.code());
                if (language == Language.EN && DUTCH_IN_EN.matcher(name).find()) {
                    throw new IllegalStateException("Engelse familienaam bevat Nederlands: " + name);
                }
                required(bundle.profiles().get(family.getValue().profile()).get(language),
                        family.getKey() + ".profile." + language.code());
                required(bundle.formatProfiles().get(family.getValue().profile()).get(language),
                        family.getKey() + ".format." + language.code());
                List<String> highlights = bundle.highlightProfiles()
                        .get(family.getValue().profile()).get(language);
                if (highlights == null || highlights.isEmpty()
                        || highlights.stream().anyMatch(value -> value == null || value.isBlank())) {
                    throw new IllegalStateException("Catalogushighlights ontbreken: "
                            + family.getKey() + "." + language.code());
                }
            }
        }
        bundle.variantNames().forEach((key, values) -> {
            for (Language language : Language.values()) {
                required(values.get(language), "variantNames." + key + "." + language.code());
            }
        });
        for (Language language : Language.values()) {
            String pattern = required(bundle.altPatterns().get(language),
                    "imageAltPatterns." + language.code());
            if (!placeholderSet(pattern).equals(List.of("color", "family"))) {
                throw new IllegalStateException("Alt-patroon moet {family} en {color} behouden");
            }
        }
        /* Human-reviewed glossary spots: these protect recurring high-risk machine translations. */
        requireEquals(bundle.colors(), "Navy", Language.FR, "Bleu marine");
        requireEquals(bundle.colors(), "Cherry Pink", Language.PL, "Wiśniowy róż");
        requireEquals(bundle.colors(), "Light Blue", Language.TR, "Açık mavi");
        if (!bundle.colourHexes().keySet().equals(bundle.colors().keySet())
                || bundle.colourHexes().values().stream().anyMatch(value ->
                        value == null || !value.matches("#[0-9A-F]{6}"))) {
            throw new IllegalStateException(
                    "Elke canonieke kleur moet exact één geldige #RRGGBB-kleurstaal hebben");
        }
    }

    private static Map<Language, String> requiredProfile(Bundle bundle, String key) {
        Map<Language, String> profile = bundle.profiles().get(key);
        if (profile == null) throw new IllegalStateException("Onbekend familieprofiel " + key);
        return profile;
    }

    private static Map<Language, String> requiredFormatProfile(Bundle bundle, String key) {
        Map<Language, String> profile = bundle.formatProfiles().get(key);
        if (profile == null) throw new IllegalStateException("Onbekend familieformatprofiel " + key);
        return profile;
    }

    private static Map<Language, List<String>> requiredHighlightProfile(Bundle bundle, String key) {
        Map<Language, List<String>> profile = bundle.highlightProfiles().get(key);
        if (profile == null) {
            throw new IllegalStateException("Onbekend familiehighlightprofiel " + key);
        }
        return profile;
    }

    /**
     * The colour in the asked language.
     *
     * The bundle is keyed by the colour names the import used ("Red"). A
     * seller may since have renamed a product's colour in the editor
     * ("Rood"), so the lookup also matches any translation in the bundle;
     * a colour the bundle does not know at all is kept as written rather
     * than stopping the application at startup.
     */
    private static String localizedColor(Bundle bundle, String raw, Language language) {
        if (raw == null || raw.isBlank()) return null;
        String wanted = raw.strip();
        Map<Language, String> exact = bundle.colors().get(wanted);
        if (exact == null) {
            exact = bundle.colors().values().stream()
                    .filter(translations -> translations.values().stream()
                            .anyMatch(value -> value != null && value.equalsIgnoreCase(wanted)))
                    .findFirst().orElse(null);
        }
        if (exact == null) {
            LOG.debugf("Kleur \"%s\" staat niet in de catalogusbundel; blijft zoals ingevuld", wanted);
            return wanted;
        }
        String value = exact.get(language);
        return value == null || value.isBlank() ? wanted : value;
    }

    private static String localizedSize(String raw, Language language) {
        return VariantSizes.translate(raw, language);
    }

    private static String known(String base, Language language) {
        return language == Language.EN ? base : null;
    }

    private static String knownFamilyName(
            String familyKey, String base, Language language, String current) {
        if (language == Language.EN) return base;
        if (language == Language.NL
                && "single-rose-in-acryl-glass-box".equals(familyKey)
                && "Roos op spiegeldoos".equals(current)) {
            return current;
        }
        return null;
    }

    private static String knownFamilyCopy(
            String familyKey, String field, String current, String candidate,
            Language language) {
        String dutch = knownDutchStale(current, language);
        if (dutch != null) return dutch;
        if (language == Language.EN && "preserved-single-rose-in-display".equals(familyKey)
                && "format".equals(field)
                && "12 individually presented steel roses".equals(current)) {
            return current;
        }
        if (language == Language.PL && current != null && candidate != null
                && current.contains("ekspozytor lada")
                && candidate.equals(current.replace("ekspozytor lada", "ekspozytor na ladę"))) {
            return current;
        }
        return null;
    }

    private static String knownCategoryMobileName(
            String categoryKey, Language language, String current, String base) {
        if (language == Language.EN && Objects.equals(current, base)) return base;
        String old = switch (categoryKey + ":" + language.name()) {
            case "display-roses:NL" -> "Displayrozen";
            case "display-roses:FR" -> "Roses en présentoir";
            case "display-roses:EN" -> "Display Roses";
            case "display-roses:DE" -> "Display-Rosen";
            case "display-roses:ES" -> "Rosas en expositor";
            case "display-roses:PL" -> "Róże w ekspozytorze";
            case "display-roses:PT" -> "Rosas em expositor";
            case "display-roses:TR" -> "Teşhir Gülleri";
            case "divers:NL" -> "Gepreserveerde rozen";
            case "divers:FR" -> "Roses stabilisées";
            case "divers:EN" -> "Preserved Roses";
            case "divers:DE" -> "Konservierte Rosen";
            case "divers:ES" -> "Rosas preservadas";
            case "divers:PL" -> "Róże stabilizowane";
            case "divers:PT" -> "Rosas preservadas";
            case "divers:TR" -> "Korunmuş Güller";
            case "rose-bears:NL" -> "Zeeprozen";
            case "rose-bears:FR" -> "Roses en savon";
            case "rose-bears:EN" -> "Soap Roses";
            case "rose-bears:DE" -> "Seifenrosen";
            case "rose-bears:ES" -> "Rosas de jabón";
            case "rose-bears:PL" -> "Róże mydlane";
            case "rose-bears:PT" -> "Rosas de sabão";
            case "rose-bears:TR" -> "Sabun Gülleri";
            default -> null;
        };
        return Objects.equals(current, old) ? current : null;
    }

    private static String knownDutchStale(String current, Language language) {
        return language == Language.EN && current != null
                && DUTCH_IN_EN.matcher(current).find() ? current : null;
    }

    private static String normalize(String value) {
        return value == null ? null : value.strip().replaceAll("\\s+", " ");
    }

    private static void requireSameNumbers(String english, String translated, String path) {
        Pattern number = Pattern.compile("\\d+(?:[.,]\\d+)?");
        List<String> source = number.matcher(english == null ? "" : english).results()
                .map(java.util.regex.MatchResult::group)
                .map(value -> value.replace(',', '.')).toList();
        List<String> target = number.matcher(translated == null ? "" : translated).results()
                .map(java.util.regex.MatchResult::group)
                .map(value -> value.replace(',', '.')).toList();
        if (!source.equals(target)) {
            throw new IllegalStateException("Technische getallen zijn gewijzigd in " + path
                    + ": " + source + " != " + target);
        }
    }

    private static void requireBrandParity(
            String english, String translated, String familyKey, Language language) {
        if (english != null && english.contains("Enrosed")
                && (translated == null || !translated.contains("Enrosed"))) {
            throw new IllegalStateException("Merknaam Enrosed ontbreekt in " + familyKey
                    + "." + language.code());
        }
    }

    private static String staleAlt(ProductFamilyEntity family, String rawColor) {
        if (family.name == null || family.name.isBlank()) return null;
        return rawColor == null || rawColor.isBlank()
                ? family.name : family.name + " in " + rawColor;
    }

    private static String merge(
            String current, String candidate, String knownStale,
            boolean correctKnownStale, Counter counter) {
        String wanted = candidate == null || candidate.isBlank() ? null : candidate.strip();
        if (wanted == null) return current;
        if (current == null || current.isBlank()) return wanted;
        if (correctKnownStale && knownStale != null && current.strip().equals(knownStale.strip())
                && !current.strip().equals(wanted)) {
            counter.corrected++;
            return wanted;
        }
        return current;
    }

    private List<ProductFamilyDto.AltTextDto> readAlts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw,
                    new TypeReference<List<ProductFamilyDto.AltTextDto>>() {});
        } catch (Exception exception) {
            throw new BusinessRuleException("Ongeldige bestaande afbeelding-altteksten");
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) {
            throw new BusinessRuleException("Kan afbeelding-altteksten niet serialiseren");
        }
    }

    private static List<String> placeholderSet(String value) {
        java.util.regex.Matcher matcher = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}")
                .matcher(value);
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group(1));
        return result.stream().distinct().sorted().toList();
    }

    private static void requireEquals(
            Map<String, Map<Language, String>> colors, String color,
            Language language, String expected) {
        if (!Objects.equals(colors.getOrDefault(color, Map.of()).get(language), expected)) {
            throw new IllegalStateException("Onjuiste glossaryvertaling voor " + color
                    + " in " + language.code());
        }
    }

    private static String required(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Catalogusvertaling ontbreekt: " + path);
        }
        return value.strip();
    }

    private static final class Counter { int inserted; int corrected; }
    private record CategoryCopy(
            String name, String description, String eyebrow,
            String mobileName, String navigationName, String footerName) {}
    private record FamilySeed(String profile, Map<Language, String> names) {}
    private record FamilyCopy(
            String summary, String description, String knownStaleDescription,
            String format, List<String> highlights) {}
    private record Bundle(
            String version, String sha256,
            Map<String, Map<Language, String>> profiles,
            Map<String, Map<Language, String>> formatProfiles,
            Map<String, Map<Language, List<String>>> highlightProfiles,
            Map<String, Map<Language, CategoryCopy>> categories,
            Map<String, FamilySeed> families,
            Map<String, Map<Language, FamilyCopy>> familyCopy,
            Map<String, Map<Language, String>> variantNames,
            Map<String, Map<Language, String>> colors,
            Set<String> targetVariantKeys,
            Set<String> targetImageKeys,
            Map<String, String> colourHexes,
            Map<Language, String> altPatterns,
            int expectedCategories, int expectedFamilies, int expectedVariants, int expectedImages) {}
    public record Result(
            String version, String sha256, int matchedCategories, int matchedFamilies, int matchedVariants,
            int matchedImages, int insertedRows, int correctedKnownFields) {}
}
