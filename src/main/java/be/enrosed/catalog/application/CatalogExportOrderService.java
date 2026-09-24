package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogExportOrderEntity;
import be.enrosed.shared.BusinessRuleException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@ApplicationScoped
public class CatalogExportOrderService {
    public static final int MAX_ORDERED_IDS = 10_000;
    private static final TypeReference<List<Long>> IDS = new TypeReference<>() {};
    private final EntityManager entities;
    private final ObjectMapper json;

    public CatalogExportOrderService(EntityManager entities, ObjectMapper json) {
        this.entities = entities;
        this.json = json;
    }

    public record Order(long revision, List<Long> orderedIds, Instant updatedAt) {}

    public Order get() {
        CatalogExportOrderEntity row = entities.find(CatalogExportOrderEntity.class, 1L);
        return row == null ? new Order(0, List.of(), null) : order(row);
    }

    @Transactional
    public Order save(long revision, List<Long> orderedIds) {
        validate(revision, orderedIds);
        List<Long> available = existingCustomerProducts(orderedIds);
        ensureStateRow();
        int changed = entities.createQuery("update CatalogExportOrderEntity o "
                        + "set o.orderedIdsJson = :ids, o.updatedAt = :updated, "
                        + "o.revision = o.revision + 1 where o.id = 1 and o.revision = :revision")
                .setParameter("ids", encode(available))
                .setParameter("updated", Instant.now())
                .setParameter("revision", revision)
                .executeUpdate();
        if (changed != 1) {
            throw conflict();
        }
        // Bulk compare-and-set bypasses the persistence context; refresh an already loaded row.
        CatalogExportOrderEntity row = entities.find(CatalogExportOrderEntity.class, 1L);
        entities.refresh(row);
        return order(row);
    }

    private static void validate(long revision, List<Long> ids) {
        if (revision < 0) throw new BadRequestException("revision moet nul of groter zijn");
        if (ids == null || ids.size() > MAX_ORDERED_IDS) {
            throw new BadRequestException("orderedIds is verplicht en mag maximaal 10000 producten bevatten");
        }
        Set<Long> seen = new HashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0 || !seen.add(id)) {
                throw new BadRequestException("orderedIds moet unieke, positieve gehele product-ID's bevatten");
            }
        }
    }

    private Order order(CatalogExportOrderEntity row) {
        try {
            return new Order(row.revision,
                    existingCustomerProducts(json.readValue(row.orderedIdsJson, IDS)), row.updatedAt);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Bewaarde catalogusvolgorde kon niet gelezen worden", failure);
        }
    }

    private List<Long> existingCustomerProducts(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Set<Long> available = new HashSet<>(entities.createQuery(
                        "select p.id from ProductEntity p where p.id in :ids and p.demo = false", Long.class)
                .setParameter("ids", ids).getResultList());
        return ids.stream().filter(available::contains).toList();
    }

    private String encode(List<Long> ids) {
        try {
            return json.writeValueAsString(ids);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Catalogusvolgorde kon niet opgeslagen worden", failure);
        }
    }

    private static BusinessRuleException conflict() {
        return new BusinessRuleException(
                "De catalogusvolgorde is intussen gewijzigd. Herlaad de bewaarde volgorde voordat je opnieuw opslaat.");
    }

    /** Inserting a missing singleton must never reset another editor's saved order. */
    private void ensureStateRow() {
        String database = entities.unwrap(org.hibernate.Session.class)
                .doReturningWork(connection -> connection.getMetaData().getDatabaseProductName());
        String sql = database != null && database.toLowerCase(Locale.ROOT).contains("postgresql")
                ? "insert into catalog_export_order (id, revision, ordered_ids_json) "
                    + "values (1, 0, '[]') on conflict (id) do nothing"
                : "merge into catalog_export_order t using (values (1)) s(id) on t.id = s.id "
                    + "when not matched then insert (id, revision, ordered_ids_json) values (1, 0, '[]')";
        try {
            entities.createNativeQuery(sql).executeUpdate();
        } catch (org.hibernate.exception.ConstraintViolationException race) {
            // H2 may resolve two absent-row MERGEs as a duplicate insert. PostgreSQL's
            // ON CONFLICT waits safely; on H2 the losing transaction must roll back.
            if ("23505".equals(race.getSQLState())) throw conflict();
            throw race;
        }
    }
}
