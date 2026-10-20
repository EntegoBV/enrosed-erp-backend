package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.application.CostParties;
import be.enrosed.finance.domain.CostParty;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import be.enrosed.shared.BusinessRuleException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Collectors;

@ApplicationScoped
public class PanacheCostParties implements CostParties {

    private final CostPartyDao dao;
    private final EntityManager entityManager;

    public PanacheCostParties(CostPartyDao dao, EntityManager entityManager) {
        this.dao = dao;
        this.entityManager = entityManager;
    }

    @Override
    public List<CostParty> findAll() {
        List<CostPartyEntity> rows = dao.listAll(Sort.ascending("nameKey"));
        var aliasesByTarget = rows.stream().filter(row -> row.mergedIntoId != null)
                .collect(Collectors.groupingBy(row -> row.mergedIntoId,
                        Collectors.mapping(row -> row.name, Collectors.toList())));
        return rows.stream().filter(row -> row.mergedIntoId == null)
                .map(row -> row.toDomain().withAliases(aliasesByTarget.getOrDefault(row.id, List.of()))).toList();
    }

    @Override
    public Optional<CostParty> findByKey(String key) {
        if (key == null) return Optional.empty();
        CostPartyEntity entity = dao.find("nameKey", key).firstResult();
        var seen = new HashSet<Long>();
        while (entity != null && entity.mergedIntoId != null) {
            if (!seen.add(entity.id)) throw new IllegalStateException("Circular cost party alias");
            entity = dao.findById(entity.mergedIntoId);
        }
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<CostParty> findById(long id) {
        return Optional.ofNullable(dao.findById(id)).map(this::toDomain);
    }

    @Override
    public CostParty save(CostParty party) {
        CostPartyEntity entity = party.id() == null ? null : dao.findById(party.id());
        if (entity == null) entity = new CostPartyEntity();
        else {
            if (entity.mergedIntoId != null) throw new BusinessRuleException("Herstel deze samengevoegde naam voordat je hem bewerkt");
            entity.updatedAt = Instant.now();
        }
        entity.apply(party);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        return toDomain(entity);
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
    }

    @Override
    public void lock(long... ids) {
        Arrays.stream(ids).distinct().sorted().forEach(id -> {
            CostPartyEntity entity = entityManager.find(CostPartyEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (entity != null) entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        });
    }

    @Override
    public Long mergedInto(long id) {
        CostPartyEntity entity = dao.findById(id);
        return entity == null ? null : entity.mergedIntoId;
    }

    @Override
    public boolean isMergedKey(String key) {
        return key != null && dao.count("nameKey = ?1 and mergedIntoId is not null", key) > 0;
    }

    @Override
    public List<Alias> aliases(long targetId) {
        return dao.list("mergedIntoId", Sort.ascending("nameKey"), targetId).stream()
                .map(row -> new Alias(row.toDomain(), targetId, row.mergedAt, row.mergedBy)).toList();
    }

    @Override
    public void setMergedInto(long sourceId, Long targetId, Instant at, String actor) {
        CostPartyEntity entity = dao.findById(sourceId);
        entity.mergedIntoId = targetId;
        entity.mergedAt = targetId == null ? null : at;
        entity.mergedBy = targetId == null ? null : actor;
        dao.flush();
    }

    private CostParty toDomain(CostPartyEntity entity) {
        return entity.toDomain().withAliases(aliases(entity.id).stream().map(alias -> alias.party().name()).toList());
    }

    /**
     * The database only strips the ends of a name; the list collapses inner
     * spaces too. So the match is made here, in Java, on the distinct stored
     * spellings: every spelling with the old key is renamed, by id.
     */
    @Override
    public Renamed renameEverywhere(String oldName, String newName) {
        String key = CostParty.keyOf(oldName);
        if (key == null) return Renamed.NONE;
        return new Renamed(rename("CompanyCostEntity", "party", key, newName),
                rename("RecurringCostEntity", "party", key, newName),
                rename("BankStatementLineEntity", "counterparty", key, newName));
    }

    private int rename(String entity, String column, String key, String newName) {
        List<String> spellings = entityManager
                .createQuery("select distinct e." + column + " from " + entity + " e where e." + column + " is not null", String.class)
                .getResultList().stream().filter(value -> key.equals(CostParty.keyOf(value))).toList();
        if (spellings.isEmpty()) return 0;
        List<Long> ids = entityManager
                .createQuery("select e.id from " + entity + " e where e." + column + " in :spellings", Long.class)
                .setParameter("spellings", spellings).getResultList();
        if (ids.isEmpty()) return 0;
        return entityManager.createQuery("update " + entity + " e set e." + column + " = :name where e.id in :ids")
                .setParameter("name", newName).setParameter("ids", ids).executeUpdate();
    }
}
