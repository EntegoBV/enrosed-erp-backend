package be.enrosed.catalog.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ProductCostHistoryDao implements PanacheRepository<ProductCostHistoryEntity> {}
