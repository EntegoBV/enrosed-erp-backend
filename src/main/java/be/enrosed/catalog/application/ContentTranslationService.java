package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.ContentTranslationDto;
import be.enrosed.catalog.adapter.in.rest.LocalizedValueDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.ContentTranslationEntity;
import be.enrosed.catalog.adapter.out.persistence.ContentTranslationTextEntity;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.LanguageFallback;
import be.enrosed.shared.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Dashboard-owned source of truth for public website and catalogue copy. */
@ApplicationScoped
public class ContentTranslationService {
    /* Contract keys are stable camelCase paths (for example a11y.productCount). */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final int MAX_VALUE_LENGTH = 10_000;

    private final CanonicalCatalogDaos.ContentTranslations rows;

    @Inject
    WebsiteRebuildService websiteRebuild;

    public ContentTranslationService(CanonicalCatalogDaos.ContentTranslations rows) {
        this.rows = rows;
    }

    public ContentTranslationDto.IndexDto index(ContentScope scope) {
        List<ContentTranslationDto.LanguageDto> languages = Arrays.stream(Language.values())
                .map(language -> new ContentTranslationDto.LanguageDto(
                        language, language.code(), language.label()))
                .toList();
        List<ContentTranslationDto> groups = list(scope).stream()
                .map(ContentTranslationDto::from).toList();
        return new ContentTranslationDto.IndexDto(languages, groups);
    }

    public ContentTranslationDto get(ContentScope scope, String key) {
        return ContentTranslationDto.from(entity(scope, key));
    }

    @Transactional
    public ContentTranslationDto create(ContentTranslationDto.CreateDto request) {
        if (request == null || request.scope() == null) {
            throw new BusinessRuleException("Copy-scope is verplicht");
        }
        String key = validKey(request.key());
        if (find(request.scope(), key) != null) {
            throw new BusinessRuleException("Copy-key " + key + " bestaat al");
        }
        ContentTranslationEntity entity = new ContentTranslationEntity();
        entity.scope = request.scope();
        entity.key = key;
        entity.label = requiredText(request.label(), "Label", 255);
        entity.required = request.required();
        entity.system = false;
        entity.updatedAt = Instant.now();
        replace(entity, requestedValues(request.texts()));
        validateRequiredLanguages(entity);
        validatePlaceholderParity(entity);
        rows.persist(entity);
        rows.flush();
        queueWebsite(request.scope());
        return ContentTranslationDto.from(entity);
    }

    @Transactional
    public ContentTranslationDto update(
            ContentScope scope, String key, ContentTranslationDto.UpdateDto request) {
        if (request == null) throw new BusinessRuleException("Geen copyvertalingen meegestuurd");
        ContentTranslationEntity entity = lockedEntity(scope, key);
        requireRevision(entity, request.revision());
        String label = requiredText(request.label(), "Label", 255);
        if (entity.system && (!Objects.equals(entity.label, label)
                || entity.required != request.required())) {
            throw new BusinessRuleException(
                    "Label en verplichting van een systeem-copy-key zijn niet wijzigbaar");
        }
        EnumMap<Language, String> values = requestedValues(request.texts());
        if (Objects.equals(entity.label, label) && entity.required == request.required()
                && currentValues(entity).equals(values)) {
            return ContentTranslationDto.from(entity);
        }
        entity.label = label;
        entity.required = request.required();
        replace(entity, values);
        validateRequiredLanguages(entity);
        validatePlaceholderParity(entity);
        entity.updatedAt = Instant.now();
        rows.flush();
        queueWebsite(scope);
        return ContentTranslationDto.from(entity);
    }

    @Transactional
    public void delete(ContentScope scope, String key, long revision) {
        ContentTranslationEntity entity = lockedEntity(scope, key);
        requireRevision(entity, revision);
        if (entity.system || entity.required) {
            throw new BusinessRuleException("Systeem- of verplichte copy-key " + entity.key
                    + " kan niet verwijderd worden");
        }
        rows.delete(entity);
        queueWebsite(scope);
    }

    public ResolvedCopy resolve(ContentScope scope, Language requested) {
        List<ContentTranslationEntity> groups = list(scope);
        LinkedHashMap<String, LocalizedValueDto> values = new LinkedHashMap<>();
        for (ContentTranslationEntity entity : groups) {
            LanguageFallback.Resolved<String> resolved = LanguageFallback.text(
                    entity.texts, requested, text -> text.language, text -> text.value, null);
            if (resolved.value() != null && !resolved.value().isBlank()) {
                values.put(entity.key, new LocalizedValueDto(
                        resolved.sourceLanguage(), resolved.value()));
            }
        }
        long revision = groups.stream()
                .map(entity -> entity.updatedAt)
                .filter(Objects::nonNull)
                .mapToLong(Instant::toEpochMilli).max().orElse(0L);
        return new ResolvedCopy(revision, Collections.unmodifiableMap(values));
    }

    public Map<String, String> values(ContentScope scope, Language requested) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        resolve(scope, requested).values().forEach(
                (key, value) -> result.put(key, value.value()));
        return Collections.unmodifiableMap(result);
    }

    /** Required keys that do not have an exact value for a strict website build. */
    public List<String> missingRequired(ContentScope scope, Language requested) {
        return list(scope).stream()
                .filter(entity -> entity.required)
                .filter(entity -> entity.texts.stream().noneMatch(text ->
                        text.language == requested && text.value != null && !text.value.isBlank()))
                .map(entity -> entity.key)
                .toList();
    }

    public List<ContentTranslationEntity> list(ContentScope scope) {
        if (scope == null) {
            return rows.findAll(io.quarkus.panache.common.Sort.by("scope").and("key")).list();
        }
        return rows.list("scope = ?1 order by key", scope);
    }

    private ContentTranslationEntity entity(ContentScope scope, String key) {
        ContentTranslationEntity entity = find(scope, validKey(key));
        if (entity == null) throw new NotFoundException("Copy-key", key);
        return entity;
    }

    private ContentTranslationEntity lockedEntity(ContentScope scope, String key) {
        String valid = validKey(key);
        ContentTranslationEntity entity = rows.find(
                "scope = ?1 and key = ?2", scope, valid)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (entity == null) throw new NotFoundException("Copy-key", key);
        return entity;
    }

    private ContentTranslationEntity find(ContentScope scope, String key) {
        return rows.find("scope = ?1 and key = ?2", scope, key).firstResult();
    }

    private static EnumMap<Language, String> requestedValues(
            List<LocalizedValueDto> requested) {
        EnumMap<Language, String> values = new EnumMap<>(Language.class);
        EnumSet<Language> seen = EnumSet.noneOf(Language.class);
        for (LocalizedValueDto input : requested == null ? List.<LocalizedValueDto>of() : requested) {
            if (input == null || input.language() == null) {
                throw new BusinessRuleException("Taal is verplicht voor elke copywaarde");
            }
            if (!seen.add(input.language())) {
                throw new BusinessRuleException("Elke copytaal mag exact één keer voorkomen");
            }
            String value = optionalText(input.value(), MAX_VALUE_LENGTH);
            if (value == null) continue;
            values.put(input.language(), value);
        }
        return values;
    }

    private static EnumMap<Language, String> currentValues(ContentTranslationEntity entity) {
        EnumMap<Language, String> result = new EnumMap<>(Language.class);
        entity.texts.forEach(text -> result.put(text.language, text.value));
        return result;
    }

    /** Reconciles rows in place so the unique owner/language constraint is never delete-insert raced. */
    private static void replace(
            ContentTranslationEntity entity, EnumMap<Language, String> requested) {
        EnumMap<Language, String> values = new EnumMap<>(requested);
        entity.texts.removeIf(existing -> !values.containsKey(existing.language));
        for (ContentTranslationTextEntity existing : entity.texts) {
            existing.value = values.remove(existing.language);
        }
        for (Map.Entry<Language, String> value : values.entrySet()) {
            ContentTranslationTextEntity text = new ContentTranslationTextEntity();
            text.owner = entity;
            text.language = value.getKey();
            text.value = value.getValue();
            entity.texts.add(text);
        }
    }

    private static void validateRequiredLanguages(ContentTranslationEntity entity) {
        if (!entity.required) return;
        List<String> missing = Arrays.stream(Language.values()).filter(language ->
                entity.texts.stream().noneMatch(text -> text.language == language
                        && text.value != null && !text.value.isBlank()))
                .map(Language::code).toList();
        if (!missing.isEmpty()) {
            throw new BusinessRuleException("Verplichte copy-key mist talen: "
                    + String.join(", ", missing));
        }
    }

    static void validatePlaceholderParity(ContentTranslationEntity entity) {
        if (!entity.system) return;
        String english = entity.texts.stream()
                .filter(text -> text.language == Language.EN)
                .map(text -> text.value).findFirst().orElse("");
        Set<String> expected = placeholders(english);
        for (ContentTranslationTextEntity text : entity.texts) {
            Set<String> actual = placeholders(text.value);
            if (!actual.equals(expected)) {
                throw new jakarta.ws.rs.BadRequestException(
                        "Copy-key " + entity.key + " taal " + text.language.code()
                                + " moet exact placeholders " + expected + " behouden");
            }
        }
    }

    private static Set<String> placeholders(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(value == null ? "" : value);
        while (matcher.find()) result.add(matcher.group(1));
        return Set.copyOf(result);
    }

    private static void requireRevision(ContentTranslationEntity entity, Long revision) {
        if (revision == null) {
            throw new BusinessRuleException("revision is verplicht bij bewaren");
        }
        if (entity.revision != revision) {
            throw new BusinessRuleException("Copy is intussen gewijzigd; herlaad voor je bewaart");
        }
    }

    private static String validKey(String value) {
        String key = optionalText(value, 180);
        if (key == null || !KEY.matcher(key).matches()) {
            throw new BusinessRuleException(
                    "Copy-key mag alleen letters, cijfers, punten en koppeltekens bevatten");
        }
        return key;
    }

    private static String requiredText(String value, String label, int max) {
        String result = optionalText(value, max);
        if (result == null) throw new BusinessRuleException(label + " is verplicht");
        return result;
    }

    private static String optionalText(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.length() > max) {
            throw new BusinessRuleException("Copy is langer dan " + max + " tekens");
        }
        return result;
    }

    private void queueWebsite(ContentScope scope) {
        if (scope == ContentScope.WEBSITE && websiteRebuild != null) websiteRebuild.queue();
    }

    public record ResolvedCopy(long revision, Map<String, LocalizedValueDto> values) {}
}
