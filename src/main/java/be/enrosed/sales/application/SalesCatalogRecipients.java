package be.enrosed.sales.application;

import be.enrosed.catalog.application.port.out.CatalogRecipients;
import be.enrosed.sales.application.port.out.SalesRepositories;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** The catalogue's view of our customers and their klantprijzen. */
@ApplicationScoped
public class SalesCatalogRecipients implements CatalogRecipients {

    private final SalesRepositories.Customers customers;
    private final CustomerPriceService prices;

    public SalesCatalogRecipients(SalesRepositories.Customers customers, CustomerPriceService prices) {
        this.customers = customers;
        this.prices = prices;
    }

    @Override
    public Optional<Customer> customer(long customerId) {
        return customers.findById(customerId)
                .map(customer -> new Customer(customer.id(), customer.company() == null ? "" : customer.company().strip()));
    }

    @Override
    public Map<Long, StoredPrice> prices(long customerId, Collection<Long> productIds) {
        return prices.stored(customerId, productIds);
    }
}
