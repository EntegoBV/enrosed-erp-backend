package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.application.Fairs;
import be.enrosed.finance.domain.Fair;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PanacheFairs implements Fairs {

    private final FairDao dao;

    public PanacheFairs(FairDao dao) {
        this.dao = dao;
    }

    @Override
    public List<Fair> findAll() {
        return dao.listAll().stream().map(FairEntity::toDomain)
                .sorted(Comparator.comparing(Fair::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public Optional<Fair> findById(long id) {
        return Optional.ofNullable(dao.findById(id)).map(FairEntity::toDomain);
    }

    @Override
    public Optional<Fair> findByCode(String code) {
        if (code == null) return Optional.empty();
        return dao.find("code", code).firstResultOptional().map(FairEntity::toDomain);
    }

    @Override
    public Fair save(Fair fair) {
        FairEntity entity = fair.id() == null ? null : dao.findById(fair.id());
        if (entity == null) entity = new FairEntity();
        entity.apply(fair);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        return entity.toDomain();
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
    }
}
