package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerCustomerQueries;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerLoginEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.sales.application.CustomerDeletionListener;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A customer with meldingen stays (their stock movements and invoices point at it), and so does a
 * customer that is the buyer of a melding (voided ones included, archive rows not); otherwise its
 * portal login, the login's sessions, its portal settings, its default count units and every
 * partner-customer link that names it go with it.
 *
 * <p>Any partner may name any customer as the buyer of a direct sale, and a melding checks its buyer
 * under that partner's lock. So the delete first takes every partner's lock (ascending, the order every
 * other multi-partner write uses), then checks: a melding that names this customer either committed
 * before (the check sees it and refuses) or waits and then finds the customer gone ("Onbekende klant").
 */
@ApplicationScoped
public class PartnerPortalCustomerDeletionListener implements CustomerDeletionListener {
    private final PartnerPortalRepositories.Logins logins;
    private final PartnerPortalRepositories.Sessions sessions;
    private final PartnerPortalRepositories.Settings settings;
    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.ProductUnits units;
    private final PartnerPortalRepositories.CustomerLinks links;
    private final PartnerCustomerQueries customerQueries;

    public PartnerPortalCustomerDeletionListener(PartnerPortalRepositories.Logins logins,
                                                 PartnerPortalRepositories.Sessions sessions,
                                                 PartnerPortalRepositories.Settings settings,
                                                 PartnerPortalRepositories.Reports reports,
                                                 PartnerPortalRepositories.ProductUnits units,
                                                 PartnerPortalRepositories.CustomerLinks links,
                                                 PartnerCustomerQueries customerQueries) {
        this.logins = logins;
        this.sessions = sessions;
        this.settings = settings;
        this.reports = reports;
        this.units = units;
        this.links = links;
        this.customerQueries = customerQueries;
    }

    @Override
    public void beforeDelete(long customerId) {
        /* Every partner's lock first: a melding naming this customer as its buyer checks it under that lock. */
        for (Long partnerId : customerQueries.partnerIds()) settings.lockOrCreate(partnerId);
        if (reports.existsForCustomer(customerId)) {
            throw new BusinessRuleException("Deze klant heeft veilingverkopen");
        }
        java.util.List<Long> sold = reports.idsWithBuyer(customerId);
        if (!sold.isEmpty()) {
            throw new BusinessRuleException("Deze klant staat op een directe verkoop ("
                    + String.join(", ", sold.stream().limit(3).map(PartnerPortalRules::reportNumber).toList())
                    + (sold.size() > 3 ? " …" : "") + ")");
        }
        PartnerLoginEntity login = logins.findByCustomer(customerId).orElse(null);
        if (login != null) {
            sessions.deleteByLogin(login.id);
            logins.delete(login);
        }
        settings.deleteById(customerId);
        units.deleteByCustomer(customerId);
        links.deleteByEither(customerId);
    }
}
