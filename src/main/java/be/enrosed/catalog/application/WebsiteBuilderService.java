package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.WebsiteBuilderDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.WebsiteHomepageLayoutEntity;
import be.enrosed.catalog.domain.HomepageSectionKey;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Draft/publish workflow for the strictly allowlisted homepage layout. */
@ApplicationScoped
public class WebsiteBuilderService {
    private static final TypeReference<List<WebsiteBuilderDto.SectionDto>> SECTION_LIST =
            new TypeReference<>() {};

    private final CanonicalCatalogDaos.WebsiteHomepageLayouts rows;
    private final WebsiteRebuildService rebuilds;
    private final ObjectMapper json;

    public WebsiteBuilderService(
            CanonicalCatalogDaos.WebsiteHomepageLayouts rows,
            WebsiteRebuildService rebuilds,
            ObjectMapper json) {
        this.rows = rows;
        this.rebuilds = rebuilds;
        this.json = json;
    }

    public WebsiteBuilderDto.AdminDto get() {
        WebsiteHomepageLayoutEntity entity = rows.findById(1L);
        return entity == null ? defaultAdmin() : admin(entity);
    }

    public WebsiteBuilderDto.PublicDto published() {
        WebsiteHomepageLayoutEntity entity = rows.findById(1L);
        if (entity == null) {
            return new WebsiteBuilderDto.PublicDto(0,
                    new WebsiteBuilderDto.HomepageDto(defaultSections()));
        }
        return new WebsiteBuilderDto.PublicDto(entity.publishedRevision,
                new WebsiteBuilderDto.HomepageDto(decode(entity.publishedSectionsJson)));
    }

    @Transactional
    public WebsiteBuilderDto.AdminDto update(WebsiteBuilderDto.UpdateDto request) {
        if (request == null) throw new BusinessRuleException("Geen homepage-layout meegestuurd");
        List<WebsiteBuilderDto.SectionDto> sections = validate(request.sections());
        WebsiteHomepageLayoutEntity entity = lockedState();
        requireRevision(entity, request.revision());
        String encoded = encode(sections);
        if (Objects.equals(encoded, entity.draftSectionsJson)) return admin(entity);

        entity.draftSectionsJson = encoded;
        entity.revision++;
        entity.updatedAt = Instant.now();
        rows.flush();
        return admin(entity);
    }

    @Transactional
    public WebsiteBuilderDto.AdminDto publish(WebsiteBuilderDto.PublishDto request) {
        if (request == null) throw new BusinessRuleException("Geen publicatierevisie meegestuurd");
        WebsiteHomepageLayoutEntity entity = lockedState();
        requireRevision(entity, request.revision());
        if (Objects.equals(entity.draftSectionsJson, entity.publishedSectionsJson)) {
            return admin(entity);
        }

        // Decode and validate stored state again at the publication boundary.
        List<WebsiteBuilderDto.SectionDto> draft = decode(entity.draftSectionsJson);
        entity.publishedSectionsJson = encode(draft);
        entity.revision++;
        entity.publishedRevision = entity.revision;
        Instant now = Instant.now();
        entity.updatedAt = now;
        entity.publishedAt = now;
        rows.flush();

        // The rebuild digest reads only published state, so draft saves never enqueue a build.
        rebuilds.queue();
        return admin(entity);
    }

    private WebsiteBuilderDto.AdminDto admin(WebsiteHomepageLayoutEntity entity) {
        return new WebsiteBuilderDto.AdminDto(
                entity.revision,
                new WebsiteBuilderDto.LayoutDto(decode(entity.draftSectionsJson)),
                new WebsiteBuilderDto.LayoutDto(decode(entity.publishedSectionsJson)),
                entity.updatedAt,
                entity.publishedAt);
    }

    private static WebsiteBuilderDto.AdminDto defaultAdmin() {
        WebsiteBuilderDto.LayoutDto layout = new WebsiteBuilderDto.LayoutDto(defaultSections());
        return new WebsiteBuilderDto.AdminDto(0, layout, layout, null, null);
    }

    private WebsiteHomepageLayoutEntity lockedState() {
        WebsiteHomepageLayoutEntity entity = rows.findById(1L, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) {
            ensureStateRow();
            entity = rows.findById(1L, LockModeType.PESSIMISTIC_WRITE);
            if (entity == null) {
                throw new IllegalStateException(
                        "Homepage-layout kon niet atomair aangemaakt worden");
            }
        }
        return entity;
    }

    /** Conflict-safe singleton creation for both PostgreSQL and the local H2 database. */
    private void ensureStateRow() {
        String database = rows.getEntityManager().unwrap(org.hibernate.Session.class)
                .doReturningWork(connection -> connection.getMetaData().getDatabaseProductName());
        String sql = database != null && database.toLowerCase(Locale.ROOT).contains("postgresql")
                ? "insert into website_homepage_layout "
                    + "(id, row_revision, revision, published_revision, "
                    + "draft_sections_json, published_sections_json) "
                    + "values (1, 0, 0, 0, ?1, ?2) on conflict (id) do nothing"
                : "merge into website_homepage_layout "
                    + "(id, row_revision, revision, published_revision, "
                    + "draft_sections_json, published_sections_json) key(id) "
                    + "values (1, 0, 0, 0, ?1, ?2)";
        String defaults = encode(defaultSections());
        rows.getEntityManager().createNativeQuery(sql)
                .setParameter(1, defaults)
                .setParameter(2, defaults)
                .executeUpdate();
        rows.flush();
    }

    private static void requireRevision(WebsiteHomepageLayoutEntity entity, Long revision) {
        if (revision == null) {
            throw new BusinessRuleException("revision is verplicht bij bewaren");
        }
        if (entity.revision != revision) {
            throw new BusinessRuleException(
                    "Homepage-layout is intussen gewijzigd; herlaad voor je bewaart");
        }
    }

    private static List<WebsiteBuilderDto.SectionDto> validate(
            List<WebsiteBuilderDto.SectionDto> requested) {
        if (requested == null) {
            throw new BusinessRuleException("Homepage-secties zijn verplicht");
        }
        if (requested.size() != HomepageSectionKey.defaultOrder().size()) {
            throw new BusinessRuleException(
                    "De homepage-layout moet elke toegestane sectie exact één keer bevatten");
        }

        EnumSet<HomepageSectionKey> seen = EnumSet.noneOf(HomepageSectionKey.class);
        for (WebsiteBuilderDto.SectionDto section : requested) {
            if (section == null || section.key() == null) {
                throw new BusinessRuleException("Elke homepage-sectie moet een geldige key hebben");
            }
            if (!seen.add(section.key())) {
                throw new BusinessRuleException(
                        "Homepage-sectie '" + section.key().key() + "' komt meer dan één keer voor");
            }
        }
        if (!seen.equals(EnumSet.allOf(HomepageSectionKey.class))) {
            throw new BusinessRuleException(
                    "De homepage-layout moet elke toegestane sectie exact één keer bevatten");
        }

        WebsiteBuilderDto.SectionDto first = requested.getFirst();
        if (first.key() != HomepageSectionKey.HERO || !first.enabled()) {
            throw new BusinessRuleException("hero moet altijd als eerste en ingeschakeld staan");
        }
        WebsiteBuilderDto.SectionDto last = requested.getLast();
        if (last.key() != HomepageSectionKey.QUOTE || !last.enabled()) {
            throw new BusinessRuleException("quote moet altijd als laatste en ingeschakeld staan");
        }
        return List.copyOf(requested);
    }

    private List<WebsiteBuilderDto.SectionDto> decode(String encoded) {
        try {
            return validate(json.readValue(encoded, SECTION_LIST));
        } catch (BusinessRuleException exception) {
            throw new IllegalStateException("Opgeslagen homepage-layout is ongeldig", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Opgeslagen homepage-layout kon niet gelezen worden", exception);
        }
    }

    private String encode(List<WebsiteBuilderDto.SectionDto> sections) {
        try {
            return json.writeValueAsString(sections);
        } catch (Exception exception) {
            throw new IllegalStateException("Homepage-layout kon niet opgeslagen worden", exception);
        }
    }

    static List<WebsiteBuilderDto.SectionDto> defaultSections() {
        return HomepageSectionKey.defaultOrder().stream()
                .map(key -> new WebsiteBuilderDto.SectionDto(key, key.enabledByDefault()))
                .toList();
    }
}
