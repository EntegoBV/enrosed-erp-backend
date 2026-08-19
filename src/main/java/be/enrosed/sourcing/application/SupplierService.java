package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.Supplier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class SupplierService {

    private final SourcingRepositories.Suppliers suppliers;
    private final ProductRepository products;

    public SupplierService(SourcingRepositories.Suppliers suppliers, ProductRepository products) {
        this.suppliers = suppliers;
        this.products = products;
    }

    public List<Supplier> list() {
        return suppliers.findAll();
    }

    public Supplier get(long id) {
        return suppliers.findById(id).orElseThrow(() -> new NotFoundException("Leverancier", id));
    }

    @Transactional
    public Supplier save(Supplier supplier) {
        if (supplier == null) {
            throw new BusinessRuleException("Geen leveranciersgegevens meegestuurd");
        }
        if (supplier.name() == null || supplier.name().isBlank()) {
            throw new BusinessRuleException("Naam is verplicht");
        }
        if (supplier.leadTimeDays() < 0) {
            throw new BusinessRuleException("Levertijd kan niet negatief zijn");
        }
        return suppliers.save(supplier);
    }

    @Transactional
    public void delete(long id) {
        Supplier supplier = get(id);
        long attached = products.countBySupplier(id);
        if (attached > 0) {
            throw new BusinessRuleException(
                    "Er hangen nog " + attached + " product(en) aan " + supplier.name());
        }
        suppliers.deleteById(id);
    }

    public long productCount(long supplierId) {
        return products.countBySupplier(supplierId);
    }
}
