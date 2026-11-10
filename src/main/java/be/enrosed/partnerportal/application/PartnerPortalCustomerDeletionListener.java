package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerLoginEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.sales.application.CustomerDeletionListener;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A customer with meldingen stays (their stock movements and invoices point at it); without any,
 * its portal login, the login's sessions and its portal settings go with it.
 */
@ApplicationScoped
public class PartnerPortalCustomerDeletionListener implements CustomerDeletionListener {
    private final PartnerPortalRepositories.Logins logins;
    private final PartnerPortalRepositories.Sessions sessions;
    private final PartnerPortalRepositories.Settings settings;
    private final PartnerPortalRepositories.Reports reports;

    public PartnerPortalCustomerDeletionListener(PartnerPortalRepositories.Logins logins,
                                                 PartnerPortalRepositories.Sessions sessions,
                                                 PartnerPortalRepositories.Settings settings,
                                                 PartnerPortalRepositories.Reports reports) {
        this.logins = logins;
        this.sessions = sessions;
        this.settings = settings;
        this.reports = reports;
    }

    @Override
    public void beforeDelete(long customerId) {
        if (reports.existsForCustomer(customerId)) {
            throw new BusinessRuleException("Deze klant heeft veilingverkopen");
        }
        PartnerLoginEntity login = logins.findByCustomer(customerId).orElse(null);
        if (login != null) {
            sessions.deleteByLogin(login.id);
            logins.delete(login);
        }
        settings.deleteById(customerId);
    }
}
