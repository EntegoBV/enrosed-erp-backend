package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerCustomerLinkEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.sales.application.CustomerPartnerOrigins;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The partner portal's answer to sales' {@link CustomerPartnerOrigins}: a customer a partner added itself
 * (a partner_customer_link with added_by_partner) shows where it came from; the first such link counts.
 */
@ApplicationScoped
public class PartnerCustomerOriginReader implements CustomerPartnerOrigins {

    private final PartnerPortalRepositories.CustomerLinks links;
    private final SalesRepositories.Customers customers;

    public PartnerCustomerOriginReader(PartnerPortalRepositories.CustomerLinks links,
                                       SalesRepositories.Customers customers) {
        this.links = links;
        this.customers = customers;
    }

    @Override
    @Transactional
    public Map<Long, Origin> origins(Collection<Long> customerIds) {
        Map<Long, Origin> result = new HashMap<>();
        Map<Long, String> partnerNames = new HashMap<>();
        for (PartnerCustomerLinkEntity link : links.addedFor(customerIds)) {
            if (link.customerId == null || result.containsKey(link.customerId)) continue;
            String partner = link.partnerCustomerId == null ? null : partnerNames.computeIfAbsent(link.partnerCustomerId,
                    id -> customers.findById(id).map(Customer::company).orElse(null));
            result.put(link.customerId, of(link, partner));
        }
        return result;
    }

    @Override
    @Transactional
    public boolean awaitsReview(long customerId) {
        return links.addedFor(java.util.List.of(customerId)).stream().anyMatch(link -> link.reviewedAt == null);
    }

    static Origin of(PartnerCustomerLinkEntity link, String partnerName) {
        return new Origin(partnerName, link.createdByName, link.createdAt, link.reviewedAt);
    }
}
