package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.application.CostParties;
import be.enrosed.finance.domain.CostParty;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
        return dao.listAll(Sort.ascending("nameKey")).stream().map(CostPartyEntity::toDomain).toList();
    }

    @Override
    public Optional<CostParty> findByKey(String key) {
        if (key == null) return Optional.empty();
        return dao.find("nameKey", key).firstResultOptional().map(CostPartyEntity::toDomain);
    }

    @Override
    public Optional<CostParty> findById(long id) {
        return Optional.ofNullable(dao.findById(id)).map(CostPartyEntity::toDomain);
    }

    @Override
    public CostParty save(CostParty party) {
        CostPartyEntity entity = party.id() == null ? null : dao.findById(party.id());
        if (entity == null) entity = new CostPartyEntity();
        else entity.updatedAt = Instant.now();
        entity.apply(party);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        return entity.toDomain();
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
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
