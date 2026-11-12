package be.enrosed.partnerportal.application;

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
 */
@ApplicationScoped
public class PartnerPortalCustomerDeletionListener implements CustomerDeletionListener {
    private final PartnerPortalRepositories.Logins logins;
    private final PartnerPortalRepositories.Sessions sessions;
    private final PartnerPortalRepositories.Settings settings;
    private final PartnerPortalRepositories.Reports reports;
    private final PartnerPortalRepositories.ProductUnits units;
    private final PartnerPortalRepositories.CustomerLinks links;

    public PartnerPortalCustomerDeletionListener(PartnerPortalRepositories.Logins logins,
                                                 PartnerPortalRepositories.Sessions sessions,
                                                 PartnerPortalRepositories.Settings settings,
                                                 PartnerPortalRepositories.Reports reports,
                                                 PartnerPortalRepositories.ProductUnits units,
                                                 PartnerPortalRepositories.CustomerLinks links) {
        this.logins = logins;
        this.sessions = sessions;
        this.settings = settings;
        this.reports = reports;
        this.units = units;
        this.links = links;
    }

    @Override
    public void beforeDelete(long customerId) {
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
