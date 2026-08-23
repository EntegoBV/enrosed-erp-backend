package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.*;
import be.enrosed.catalog.domain.ContentScope;
import be.enrosed.shared.Csv;
import be.enrosed.shared.Language;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * One-time-safe bootstrap for dashboard-owned public copy.
 *
 * Existing groups and translation rows are never overwritten. Clearing a dashboard value
 * therefore remains a deliberate, persistent edit; newly introduced seed keys are still added.
 */
@ApplicationScoped
public class PublicContentSeedLoader {
    private static final Logger LOG = Logger.getLogger(PublicContentSeedLoader.class);
    private static final String CATALOG_RESOURCE = "/i18n/public-content.csv";
    private static final String WEBSITE_RESOURCE = "/i18n/website-content.csv";
    private static final Set<String> PROTECTED_TERMS = Set.of(
            "Royal FloraHolland", "TICA", "SKU", "EAN", "B2B", "EXW", "DDP");
    private static final Set<String> RETIRED_WEBSITE_KEYS = Set.of(
            "site.nav.products", "site.product.choosevariant", "site.product.color",
            "site.product.size", "site.product.productdimensions",
            "site.product.cartondimensions", "site.product.pack", "site.product.ean",
            "site.product.priceonrequest", "site.product.imageunavailable",
            "site.catalog.emptytitle", "site.catalog.emptybody",
            "site.common.viewproduct", "site.common.back");

    private final CanonicalCatalogDaos.ContentTranslations content;
    private final CanonicalCatalogDaos.Families families;
    private final CatalogDaos.Products products;
    private final CatalogDaos.Categories categories;
    private final CatalogContentBackfillService catalogBackfill;
    private final WebsiteCatalogRevisionService websiteRevision;
    private final WebsiteRebuildService websiteRebuild;
    private final EntityManager entityManager;
    private final CatalogMutationLock mutationLock;

    public PublicContentSeedLoader(
            CanonicalCatalogDaos.ContentTranslations content,
            CanonicalCatalogDaos.Families families,
            CatalogDaos.Products products,
            CatalogDaos.Categories categories,
            CatalogContentBackfillService catalogBackfill,
            WebsiteCatalogRevisionService websiteRevision,
            WebsiteRebuildService websiteRebuild,
            EntityManager entityManager,
            CatalogMutationLock mutationLock) {
        this.content = content;
        this.families = families;
        this.products = products;
        this.categories = categories;
        this.catalogBackfill = catalogBackfill;
        this.websiteRevision = websiteRevision;
        this.websiteRebuild = websiteRebuild;
        this.entityManager = entityManager;
        this.mutationLock = mutationLock;
    }

    /* Not transactional itself: the seeding runs in its own transaction, so
       a failure rolls that back and lands here instead of poisoning the
       transaction this method would otherwise own. */
    void onStart(@Observes StartupEvent ignored) {
        SeedResult result;
        try {
            result = ensureSeededAndQueueWebsiteChange();
        } catch (RuntimeException failure) {
            /* Website copy is not worth a dead ERP: log it loudly, start anyway,
               and the next save of that family runs the backfill again. */
            LOG.error("Publieke copy kon bij het opstarten niet bijgewerkt worden; de app start zonder", failure);
            return;
        }
        LOG.infof("Publieke copy gecontroleerd: %d key(s)/taalwaarden toegevoegd",
                result.seededValues());
        if (result.retiredKeys() > 0) LOG.infof("%d verouderde website-copy-key(s) verwijderd",
                result.retiredKeys());
        LOG.infof("Catalogusvertalingen %s: %d categorieën, %d families, %d varianten, %d beelden; "
                        + "%d rijen toegevoegd, %d bekende importwaarden gecorrigeerd",
                result.backfill().version(), result.backfill().matchedCategories(),
                result.backfill().matchedFamilies(), result.backfill().matchedVariants(),
                result.backfill().matchedImages(), result.backfill().insertedRows(),
                result.backfill().correctedKnownFields());
    }

    /** Compares the complete public digest because individual seed counters are not exhaustive. */
    @Transactional
    SeedResult ensureSeededAndQueueWebsiteChange() {
        mutationLock.acquire();
        String before = websiteRevision.currentRevision();
        SeedResult result = ensureSeeded();
        entityManager.flush();
        String after = websiteRevision.currentRevision();
        if (!Objects.equals(before, after)) websiteRebuild.queue();
        return result;
    }

    /** Reusable by a same-transaction product replacement after it cleared seeded rows. */
    @Transactional
    public SeedResult ensureSeeded() {
        mutationLock.acquire();
        int retired = deleteRetiredWebsiteKeys();
        int seeded = seedPublicCopy();
        return new SeedResult(retired, seeded, catalogBackfill.apply());
    }

    public record SeedResult(
            int retiredKeys, int seededValues, CatalogContentBackfillService.Result backfill) {}

    private int seedPublicCopy() {
        int inserted = 0;
        for (Seed seed : seeds()) {
            ContentTranslationEntity existing = content.find(
                    "scope = ?1 and key = ?2", seed.scope(), seed.key())
                    .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
            if (existing != null) {
                entityManager.refresh(existing, LockModeType.PESSIMISTIC_WRITE);
                boolean changed = false;
                if (!existing.system) {
                    existing.system = true;
                    changed = true;
                }
                if (!Objects.equals(existing.label, seed.label())) {
                    existing.label = seed.label();
                    changed = true;
                }
                if (existing.required != seed.required()) {
                    existing.required = seed.required();
                    changed = true;
                }
                Map<Language, ContentTranslationTextEntity> present = new EnumMap<>(Language.class);
                existing.texts.forEach(text -> present.put(text.language, text));
                for (Map.Entry<Language, String> value : seed.values().entrySet()) {
                    ContentTranslationTextEntity presentText = present.get(value.getKey());
                    if (presentText != null) {
                        if (isKnownStaleSeedValue(seed.scope(), seed.key(), value.getKey(),
                                presentText.value)) {
                            presentText.value = value.getValue();
                            changed = true;
                        }
                        continue;
                    }
                    ContentTranslationTextEntity text = new ContentTranslationTextEntity();
                    text.owner = existing;
                    text.language = value.getKey();
                    text.value = value.getValue();
                    existing.texts.add(text);
                    changed = true;
                    inserted++;
                }
                if (changed) existing.updatedAt = Instant.now();
                ContentTranslationService.validatePlaceholderParity(existing);
                continue;
            }
            ContentTranslationEntity entity = new ContentTranslationEntity();
            entity.scope = seed.scope();
            entity.key = seed.key();
            entity.label = seed.label();
            entity.required = seed.required();
            entity.system = true;
            entity.updatedAt = Instant.now();
            for (Map.Entry<Language, String> value : seed.values().entrySet()) {
                if (value.getValue() == null || value.getValue().isBlank()) continue;
                ContentTranslationTextEntity text = new ContentTranslationTextEntity();
                text.owner = entity;
                text.language = value.getKey();
                text.value = value.getValue();
                entity.texts.add(text);
            }
            ContentTranslationService.validatePlaceholderParity(entity);
            content.persist(entity);
            inserted += entity.texts.size();
        }
        return inserted;
    }

    private int deleteRetiredWebsiteKeys() {
        int removed = 0;
        for (String key : RETIRED_WEBSITE_KEYS) {
            ContentTranslationEntity existing = content.find(
                    "scope = ?1 and key = ?2", ContentScope.WEBSITE, key)
                    .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
            if (existing == null) continue;
            entityManager.refresh(existing, LockModeType.PESSIMISTIC_WRITE);
            content.delete(existing);
            removed++;
        }
        return removed;
    }

    private static List<Seed> seeds() {
        List<Seed> result = new ArrayList<>();
        result.addAll(readSeeds(CATALOG_RESOURCE, true));
        result.addAll(readSeeds(WEBSITE_RESOURCE, false));
        Set<String> identities = new HashSet<>();
        for (Seed seed : result) {
            String identity = seed.scope() + ":" + seed.key();
            if (!identities.add(identity)) {
                throw new IllegalStateException("Dubbele public copy key " + identity);
            }
            validateSeed(seed);
        }
        return List.copyOf(result);
    }

    private static List<Seed> readSeeds(String resource, boolean hasScope) {
        try (InputStream input = PublicContentSeedLoader.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Public copy seed ontbreekt: " + resource);
            List<List<String>> rows = Csv.parseRows(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            if (rows.isEmpty()) throw new IllegalStateException("Public copy seed is leeg: " + resource);
            List<String> header = rows.getFirst();
            int fixedColumns = hasScope ? 4 : 3;
            if (header.size() != fixedColumns + Language.values().length) {
                throw new IllegalStateException(resource + " heeft een ongeldige header");
            }
            Language[] columns = new Language[header.size()];
            EnumSet<Language> seen = EnumSet.noneOf(Language.class);
            for (int index = fixedColumns; index < header.size(); index++) {
                columns[index] = Language.valueOf(header.get(index).trim().toUpperCase(Locale.ROOT));
                if (!seen.add(columns[index])) {
                    throw new IllegalStateException("Dubbele taal in public copy seed");
                }
            }
            if (seen.size() != Language.values().length) {
                throw new IllegalStateException("Public copy seed mist een ondersteunde taal");
            }
            List<Seed> result = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                List<String> cells = rows.get(rowIndex);
                int lineNumber = rowIndex + 1;
                if (cells.size() != header.size()) {
                    throw new IllegalStateException(resource + " record " + lineNumber
                            + " heeft " + cells.size() + " in plaats van " + header.size()
                            + " kolommen");
                }
                ContentScope scope = hasScope
                        ? ContentScope.valueOf(cells.get(0).trim().toUpperCase(Locale.ROOT))
                        : ContentScope.WEBSITE;
                /* The old compact WEBSITE seed used a retired `site.*` contract. Keeping it in
                   source history is harmless, but it must never re-enter the dashboard store. */
                if (hasScope && scope != ContentScope.CATALOG) continue;
                int keyIndex = hasScope ? 1 : 0;
                EnumMap<Language, String> values = new EnumMap<>(Language.class);
                for (int index = fixedColumns; index < cells.size(); index++) {
                    values.put(columns[index], cells.get(index));
                }
                result.add(new Seed(scope, cells.get(keyIndex).trim(),
                        cells.get(keyIndex + 1).trim(),
                        Boolean.parseBoolean(cells.get(keyIndex + 2).trim()), values));
            }
            return List.copyOf(result);
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Public copy seed kon niet gelezen worden: " + resource,
                    exception);
        }
    }

    private static void validateSeed(Seed seed) {
        String english = seed.values().get(Language.EN);
        if (seed.values().size() != Language.values().length
                || seed.values().values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("Public copy key " + seed.key()
                    + " moet alle acht niet-lege talen bevatten");
        }
        for (Map.Entry<Language, String> localized : seed.values().entrySet()) {
            String value = localized.getValue();
            if (containsEnrosed(english) && !containsEnrosed(value)) {
                throw new IllegalStateException("Merknaam Enrosed ontbreekt in " + seed.key()
                        + " taal " + localized.getKey().code());
            }
            for (String term : PROTECTED_TERMS) {
                if (english.contains(term) && !value.contains(term)) {
                    throw new IllegalStateException("Beschermde term " + term + " ontbreekt in "
                            + seed.key() + " taal " + localized.getKey().code());
                }
            }
            if (java.util.regex.Pattern.compile("[.!?]\\p{Lu}\\p{Ll}{2}")
                    .matcher(value.replace("B.V.", "BV")).find()) {
                throw new IllegalStateException("Waarschijnlijke ontbrekende zinspatie in "
                        + seed.key() + " taal " + localized.getKey().code());
            }
        }
    }

    private static boolean containsEnrosed(String value) {
        return value != null && java.util.regex.Pattern.compile("(?i)\\bENROSED\\b")
                .matcher(value).find();
    }

    /** Corrects only exact values shipped by an older system seed; dashboard edits survive. */
    private static boolean isKnownStaleSeedValue(
            ContentScope scope, String key, Language language, String current) {
        if (scope == ContentScope.WEBSITE && "home.counter.item3.title".equals(key)) {
            return "12 Steel Roses".equals(current);
        }
        if (scope == ContentScope.CATALOG && "catalog.common.family.plural".equals(key)
                && language == Language.TR) {
            return "AİLE".equals(current);
        }
        return scope == ContentScope.CATALOG && "catalog.brand.wholesale".equals(key)
                && language == Language.PT && "ATACADO".equals(current);
    }

    private record Seed(ContentScope scope, String key, String label, boolean required,
                        Map<Language, String> values) {}
}
