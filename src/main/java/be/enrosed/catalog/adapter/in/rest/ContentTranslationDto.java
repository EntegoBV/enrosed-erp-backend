package be.enrosed.catalog.adapter.in.rest;

import be.enrosed.catalog.adapter.out.persistence.ContentTranslationEntity;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.shared.Language;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/** Revisioned dashboard projection for website and catalogue copy. */
public record ContentTranslationDto(
        ContentScope scope,
        String key,
        String label,
        boolean required,
        boolean system,
        long revision,
        List<LocalizedValueDto> texts,
        List<Language> missingLanguages
) {
    public record LanguageDto(Language language, String code, String label) {}

    public record IndexDto(
            List<LanguageDto> languages,
            List<ContentTranslationDto> groups
    ) {}

    public record CreateDto(
            ContentScope scope,
            String key,
            String label,
            boolean required,
            List<LocalizedValueDto> texts
    ) {}

    public record UpdateDto(
            Long revision,
            String label,
            boolean required,
            List<LocalizedValueDto> texts
    ) {}

    public static ContentTranslationDto from(ContentTranslationEntity entity) {
        EnumSet<Language> present = EnumSet.noneOf(Language.class);
        List<LocalizedValueDto> texts = entity.texts.stream()
                .map(text -> {
                    present.add(text.language);
                    return new LocalizedValueDto(text.language, text.value);
                })
                .toList();
        List<Language> missing = Arrays.stream(Language.values())
                .filter(language -> !present.contains(language)).toList();
        return new ContentTranslationDto(entity.scope, entity.key, entity.label,
                entity.required, entity.system, entity.revision, texts, missing);
    }
}
