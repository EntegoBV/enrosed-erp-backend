package be.enrosed.finance.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CompanyCostDao implements PanacheRepository<CompanyCostEntity> {}
