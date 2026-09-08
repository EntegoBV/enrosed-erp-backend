package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.application.RecurringCosts;
import be.enrosed.finance.domain.RecurringCost;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PanacheRecurringCosts implements RecurringCosts {

    private final RecurringCostDao dao;

    public PanacheRecurringCosts(RecurringCostDao dao) {
        this.dao = dao;
    }

    @Override
    public List<RecurringCost> findAll() {
        return dao.listAll(Sort.descending("active").and("name", Sort.Direction.Ascending).and("id", Sort.Direction.Ascending))
                .stream().map(RecurringCostEntity::toDomain).toList();
    }

    @Override
    public Optional<RecurringCost> findById(long id) {
        return Optional.ofNullable(dao.findById(id)).map(RecurringCostEntity::toDomain);
    }

    @Override
    public RecurringCost save(RecurringCost definition) {
        RecurringCostEntity entity = definition.id() == null ? null : dao.findById(definition.id());
        if (entity == null) entity = new RecurringCostEntity();
        entity.apply(definition);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        return entity.toDomain();
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
    }
}
