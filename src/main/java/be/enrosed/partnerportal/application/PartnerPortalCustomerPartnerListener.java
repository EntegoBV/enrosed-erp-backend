package be.enrosed.partnerportal.application;

import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.sales.application.CustomerPartnerListener;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Partnercontainers switched off on the customer: the portal login signs out on every device.
 * The sign-in itself also refuses a customer that is no partner, so this only clears the rows.
 */
@ApplicationScoped
public class PartnerPortalCustomerPartnerListener implements CustomerPartnerListener {
    private final PartnerPortalRepositories.Logins logins;
    private final PartnerPortalRepositories.Sessions sessions;

    public PartnerPortalCustomerPartnerListener(PartnerPortalRepositories.Logins logins,
                                                PartnerPortalRepositories.Sessions sessions) {
        this.logins = logins;
        this.sessions = sessions;
    }

    @Override
    public void partnerEnded(long customerId) {
        logins.findByCustomer(customerId).ifPresent(login -> sessions.deleteByLogin(login.id));
    }
}
