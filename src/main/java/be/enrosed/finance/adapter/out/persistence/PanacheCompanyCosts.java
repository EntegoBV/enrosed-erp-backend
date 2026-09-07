package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.application.CompanyCosts;
import be.enrosed.finance.domain.CompanyCost;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PanacheCompanyCosts implements CompanyCosts {

    private final CompanyCostDao dao;

    public PanacheCompanyCosts(CompanyCostDao dao) {
        this.dao = dao;
    }

    @Override
    public List<CompanyCost> findBetween(LocalDate from, LocalDate to) {
        Sort sort = Sort.descending("date").and("id", Sort.Direction.Descending);
        if (from != null && to != null) {
            return dao.list("date >= ?1 and date <= ?2", sort, from, to).stream().map(CompanyCostEntity::toDomain).toList();
        }
        if (from != null) return dao.list("date >= ?1", sort, from).stream().map(CompanyCostEntity::toDomain).toList();
        if (to != null) return dao.list("date <= ?1", sort, to).stream().map(CompanyCostEntity::toDomain).toList();
        return dao.listAll(sort).stream().map(CompanyCostEntity::toDomain).toList();
    }

    @Override
    public Optional<CompanyCost> findById(long id) {
        return Optional.ofNullable(dao.findById(id)).map(CompanyCostEntity::toDomain);
    }

    @Override
    public CompanyCost save(CompanyCost cost) {
        CompanyCostEntity entity = cost.id() == null ? null : dao.findById(cost.id());
        if (entity == null) entity = new CompanyCostEntity();
        entity.apply(cost);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        return entity.toDomain();
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
    }
}
