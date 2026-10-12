package be.enrosed.finance.adapter.out.persistence;

import be.enrosed.finance.application.CostPayments;
import be.enrosed.finance.domain.CostPayment;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class PanacheCostPayments implements CostPayments {

    private static final Sort BY_DATE = Sort.ascending("paidOn").and("id", Sort.Direction.Ascending);

    private final CostPaymentDao dao;

    public PanacheCostPayments(CostPaymentDao dao) {
        this.dao = dao;
    }

    @Override
    public List<CostPayment> findByCost(long costId) {
        return dao.list("companyCostId = ?1", BY_DATE, costId).stream().map(CostPaymentEntity::toDomain).toList();
    }

    @Override
    public Map<Long, List<CostPayment>> findByCosts(Collection<Long> costIds) {
        Map<Long, List<CostPayment>> byCost = new LinkedHashMap<>();
        if (costIds == null || costIds.isEmpty()) return byCost;
        for (CostPaymentEntity entity : dao.list("companyCostId in ?1", BY_DATE, List.copyOf(costIds))) {
            byCost.computeIfAbsent(entity.companyCostId, id -> new java.util.ArrayList<>()).add(entity.toDomain());
        }
        return byCost;
    }

    @Override
    public Optional<CostPayment> findById(long id) {
        return Optional.ofNullable(dao.findById(id)).map(CostPaymentEntity::toDomain);
    }

    @Override
    public CostPayment save(CostPayment payment) {
        CostPaymentEntity entity = payment.id() == null ? null : dao.findById(payment.id());
        if (entity == null) entity = new CostPaymentEntity();
        entity.apply(payment);
        if (entity.id == null) dao.persist(entity);
        dao.flush();
        return entity.toDomain();
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
        dao.flush();
    }

    @Override
    public int deleteByCost(long costId) {
        int deleted = (int) dao.delete("companyCostId = ?1", costId);
        dao.flush();
        return deleted;
    }
}
