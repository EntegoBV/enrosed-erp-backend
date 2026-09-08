package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.application.BankBalances;
import be.enrosed.finance.domain.BankBalance;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PanacheBankBalances implements BankBalances {

    private final BankBalanceDao dao;

    public PanacheBankBalances(BankBalanceDao dao) {
        this.dao = dao;
    }

    @Override
    public List<BankBalance> findAll() {
        return dao.listAll(Sort.descending("date").and("id", Sort.Direction.Descending))
                .stream().map(BankBalanceEntity::toDomain).toList();
    }

    @Override
    public Optional<BankBalance> findById(long id) {
        return Optional.ofNullable(dao.findById(id)).map(BankBalanceEntity::toDomain);
    }

    @Override
    public BankBalance save(BankBalance balance) {
        BankBalanceEntity entity = balance.id() == null ? null : dao.findById(balance.id());
        if (entity == null) entity = new BankBalanceEntity();
        entity.apply(balance);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        return entity.toDomain();
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
    }
}
