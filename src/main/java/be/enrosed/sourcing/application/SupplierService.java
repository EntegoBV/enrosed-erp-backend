package be.enrosed.sourcing.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.Supplier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@ApplicationScoped
public class SupplierService {

    private static final Set<String> ISO_COUNTRY_CODES = Set.of(Locale.getISOCountries());
    private static final String ACTIVITY_ENTITY = "SUPPLIER";

    private final SourcingRepositories.Suppliers suppliers;
    private final ProductRepository products;

    @Inject
    Instance<ActivityLogService> activity;

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

    /** Nullable lookup for historical purchase orders whose supplier vanished. */
    public Supplier find(long id) {
        return suppliers.findById(id).orElse(null);
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
        String country = optional(supplier.country());
        if (country != null && !ISO_COUNTRY_CODES.contains(country.toUpperCase(Locale.ROOT))) {
            throw new BusinessRuleException("Onbekende ISO-landcode: " + country);
        }
        boolean created = supplier.id() == null || suppliers.findById(supplier.id()).isEmpty();
        Supplier saved = suppliers.save(normalize(supplier, country));
        recordActivity(created ? ActivityLogService.ACTION_CREATED : ActivityLogService.ACTION_UPDATED,
                saved, created ? "Leverancier aangemaakt" : "Leverancier bijgewerkt");
        return saved;
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
        recordActivity(ActivityLogService.ACTION_DELETED, supplier, "Leverancier verwijderd");
    }

    public long productCount(long supplierId) {
        return products.countBySupplier(supplierId);
    }

    private static Supplier normalize(Supplier supplier, String country) {
        return new Supplier(supplier.id(), supplier.name().strip(),
                country == null ? null : country.toUpperCase(Locale.ROOT), optional(supplier.city()),
                supplier.contact(), supplier.email(), supplier.phone(), supplier.currency(),
                supplier.incoterm(), supplier.portOfLoading(), supplier.leadTimeDays(), supplier.notes(),
                optional(supplier.addressLine1()), optional(supplier.addressLine2()),
                optional(supplier.postalCode()), optional(supplier.region()));
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** The authenticated actor is resolved inside ActivityLogService, never from the request. */
    private void recordActivity(String action, Supplier supplier, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                supplier.id() == null ? null : supplier.id().toString(), supplier.name(), summary);
    }
}
