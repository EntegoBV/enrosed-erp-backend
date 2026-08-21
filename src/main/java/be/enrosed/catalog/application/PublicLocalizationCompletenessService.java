package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ProductFamilyDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.CategoryEntity;
import be.enrosed.catalog.adapter.out.persistence.CategoryTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductFamilyTextEntity;
import be.enrosed.catalog.adapter.out.persistence.ProductTextEntity;
import be.enrosed.catalog.domain.CatalogChannel;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.catalog.domain.PublicationState;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** One lifecycle/preflight rule set for public families and strict catalogue exports. */
@ApplicationScoped
public class PublicLocalizationCompletenessService {
    private final CatalogDaos.Categories categories;
    private final ContentTranslationService content;
    private final ObjectMapper json;

    public PublicLocalizationCompletenessService(
            CatalogDaos.Categories categories,
            ContentTranslationService content,
            ObjectMapper json) {
        this.categories = categories;
        this.content = content;
        this.json = json;
    }

    public List<String> issues(ProductFamilyEntity family, List<ProductEntity> members) {
        List<String> issues = new ArrayList<>();
        if (ready(family.websiteStatus)) {
            issues.addAll(missing(family, members, CatalogChannel.WEBSITE));
        }
        if (ready(family.catalogueStatus)) {
            issues.addAll(missing(family, members, CatalogChannel.CATALOGUE));
        }
        return List.copyOf(issues);
    }

    public List<String> missing(
            ProductFamilyEntity family, List<ProductEntity> members, CatalogChannel channel) {
        ContentScope scope = channel == CatalogChannel.WEBSITE
                ? ContentScope.WEBSITE : ContentScope.CATALOG;
        String channelKey = channel == CatalogChannel.WEBSITE ? "website" : "catalog";
        String familyKey = blank(family.publicHandle) ? family.familyKey : family.publicHandle;
        String prefix = channelKey + ".families." + familyKey;
        List<String> missing = new ArrayList<>();
        CategoryEntity category = categories.listAll().stream()
                .filter(item -> Objects.equals(item.id, family.categoryId)
                        || Objects.equals(CategoryPublicKey.from(item.code),
                                CategoryPublicKey.from(family.categoryKey)))
                .findFirst().orElse(null);

        for (Language language : Language.values()) {
            String locale = language.code();
            ProductFamilyTextEntity familyText = family.texts.stream()
                    .filter(text -> text.language == language).findFirst().orElse(null);
            required(missing, prefix + "." + locale + ".name", value(familyText, text -> text.name));
            required(missing, prefix + "." + locale + ".summary", value(familyText, text -> text.summary));
            required(missing, prefix + "." + locale + ".description",
                    value(familyText, text -> text.description));
            if (familyUses(family, text -> text.format)) {
                required(missing, prefix + "." + locale + ".format",
                        value(familyText, text -> text.format));
            }
            if (familyUsesHighlights(family)) {
                if (familyText == null || readStrings(familyText.highlightsJson).isEmpty()) {
                    missing.add(prefix + "." + locale + ".highlights");
                }
            }
            /* SEO may be safely derived from exact name + summary/description. */
            if (blank(value(familyText, text -> text.seoTitle))
                    && blank(value(familyText, text -> text.name))) {
                missing.add(prefix + "." + locale + ".seoTitle");
            }
            if (blank(value(familyText, text -> text.seoDescription))
                    && blank(value(familyText, text -> text.summary))
                    && blank(value(familyText, text -> text.description))) {
                missing.add(prefix + "." + locale + ".seoDescription");
            }

            CategoryTextEntity categoryText = category == null ? null : category.texts.stream()
                    .filter(text -> text.language == language).findFirst().orElse(null);
            required(missing, prefix + ".category." + locale + ".name",
                    value(categoryText, text -> text.name));
            required(missing, prefix + ".category." + locale + ".description",
                    value(categoryText, text -> text.description));
            required(missing, prefix + ".category." + locale + ".eyebrow",
                    value(categoryText, text -> text.eyebrow));
            if (categoryUsesMobileName(category)) {
                required(missing, prefix + ".category." + locale + ".mobileName",
                        value(categoryText, text -> text.mobileName));
            }
            if (categoryUsesNavigationName(category)) {
                required(missing, prefix + ".category." + locale + ".navigationName",
                        value(categoryText, text -> text.navigationName));
            }
            if (categoryUsesFooterName(category)) {
                required(missing, prefix + ".category." + locale + ".footerName",
                        value(categoryText, text -> text.footerName));
            }

            for (ProductEntity product : members.stream().filter(item -> item.active)
                    .sorted(Comparator.comparingInt(item -> item.variantPosition)).toList()) {
                String variantKey = blank(product.canonicalVariantKey)
                        ? String.valueOf(product.id) : product.canonicalVariantKey;
                ProductTextEntity text = product.texts.stream()
                        .filter(item -> item.language == language).findFirst().orElse(null);
                if (productUses(product, product.name, item -> item.name)) {
                    required(missing, prefix + ".variants." + variantKey + "." + locale + ".name",
                            value(text, item -> item.name));
                }
                if (productUses(product, product.colour, item -> item.colour)) {
                    required(missing, prefix + ".variants." + variantKey + "." + locale + ".color",
                            value(text, item -> item.colour));
                }
                if (productUses(product, product.variantSize, item -> item.variantSize)) {
                    required(missing, prefix + ".variants." + variantKey + "." + locale + ".size",
                            value(text, item -> item.variantSize));
                }
            }

            family.photos.stream().sorted(Comparator.comparingInt(image -> image.position))
                    .forEach(image -> {
                        List<ProductFamilyDto.AltTextDto> alts = read(
                                image.altTextsJson,
                                new TypeReference<List<ProductFamilyDto.AltTextDto>>() {});
                        String alt = alts.stream().filter(item -> item.language() == language)
                                .map(ProductFamilyDto.AltTextDto::alt).findFirst().orElse(null);
                        required(missing, prefix + ".images." + image.sourceKey + "."
                                + locale + ".alt", alt);
                    });

            content.missingRequired(scope, language).forEach(key ->
                    missing.add(channelKey + ".copy." + locale + "." + key));
        }
        return List.copyOf(missing);
    }

    public void validateReadyOrPublished(ProductFamilyEntity family, List<ProductEntity> members) {
        List<String> missing = issues(family, members);
        if (!missing.isEmpty()) {
            throw new BusinessRuleException("Publicatiecopy is onvolledig: "
                    + String.join("; ", missing));
        }
    }

    private boolean familyUses(
            ProductFamilyEntity family, Function<ProductFamilyTextEntity, String> field) {
        return !blank(family.format) || family.texts.stream().map(field).anyMatch(value -> !blank(value));
    }

    private boolean familyUsesHighlights(ProductFamilyEntity family) {
        return !readStrings(family.highlightsJson).isEmpty()
                || family.texts.stream().anyMatch(text -> !readStrings(text.highlightsJson).isEmpty());
    }

    private static boolean categoryUsesMobileName(CategoryEntity category) {
        return category != null && (!blank(category.mobileName)
                || category.texts.stream().anyMatch(text -> !blank(text.mobileName)));
    }

    private static boolean categoryUsesNavigationName(CategoryEntity category) {
        return category != null && (!blank(category.navigationName)
                || category.texts.stream().anyMatch(text -> !blank(text.navigationName)));
    }

    private static boolean categoryUsesFooterName(CategoryEntity category) {
        return category != null && (!blank(category.footerName)
                || category.texts.stream().anyMatch(text -> !blank(text.footerName)));
    }

    private static boolean productUses(
            ProductEntity product, String base, Function<ProductTextEntity, String> field) {
        return !blank(base) || product.texts.stream().map(field).anyMatch(value -> !blank(value));
    }

    private List<String> readStrings(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return read(raw, new TypeReference<List<String>>() {});
    }

    private <T> T read(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (Exception exception) {
            throw new BusinessRuleException("Ongeldige publieke vertaling in de database");
        }
    }

    private static <T> String value(T row, Function<T, String> field) {
        return row == null ? null : field.apply(row);
    }

    private static void required(List<String> missing, String path, String value) {
        if (blank(value)) missing.add(path);
    }

    private static boolean ready(PublicationState state) {
        return state == PublicationState.READY || state == PublicationState.PUBLISHED;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
