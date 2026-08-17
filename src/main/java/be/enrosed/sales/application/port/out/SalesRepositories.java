package be.enrosed.sales.application.port.out;

import be.enrosed.sales.domain.*;

import java.util.List;
import java.util.Optional;

/** De uitgaande poorten van de verkoopkant, bij elkaar gehouden. */
public interface SalesRepositories {

    interface Customers {
        List<Customer> findAll();
        Optional<Customer> findById(long id);
        Customer save(Customer customer);
        void deleteById(long id);
    }

    interface Countries {
        List<Country> findAll();
        Optional<Country> findByCode(String code);
        Country save(Country country);
        void deleteByCode(String code);
    }

    interface Tiers {
        List<DiscountTier> findByScope(TierScope scope);
        void replaceScope(TierScope scope, List<DiscountTier> tiers);
    }

    interface Orders {
        List<SalesOrder> findAll();
        Optional<SalesOrder> findById(long id);
        Optional<SalesOrder> findByPortalToken(String token);
        long countByCustomer(long customerId);
        SalesOrder save(SalesOrder order);
        void deleteById(long id);
    }

    interface Revisions {
        List<QuoteRevision> findByOrder(long salesOrderId);
        List<QuoteRevision> findPending();
        Optional<QuoteRevision> findById(long id);
        QuoteRevision save(QuoteRevision revision);
    }

    /** De geschiedenis van een offerte. Alleen toevoegen en lezen. */
    interface Events {
        List<QuoteEvent> findByOrder(long salesOrderId);
        QuoteEvent add(QuoteEvent event);
    }
}
