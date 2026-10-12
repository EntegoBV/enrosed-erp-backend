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

    @Override
    public int renameEverywhere(String oldName, String newName) {
        int touched = 0;
        touched += rename("update CompanyCostEntity c set c.party = :name where lower(c.party) = lower(:old)", oldName, newName);
        touched += rename("update RecurringCostEntity r set r.party = :name where lower(r.party) = lower(:old)", oldName, newName);
        touched += rename("update BankStatementLineEntity b set b.counterparty = :name where lower(b.counterparty) = lower(:old)",
                oldName, newName);
        return touched;
    }

    private int rename(String jpql, String oldName, String newName) {
        return entityManager.createQuery(jpql).setParameter("name", newName).setParameter("old", oldName).executeUpdate();
    }
}
