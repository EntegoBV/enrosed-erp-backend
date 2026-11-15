package be.enrosed.account;

import be.enrosed.sales.application.CustomerDeletionListener;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * The login tables carry no foreign keys, so a deleted customer takes its logins, their
 * sessions, links and activity rows, and its login requests along here.
 */
@ApplicationScoped
public class AccountCustomerDeletionListener implements CustomerDeletionListener {
    @Override
    public void beforeDelete(long customerId) {
        List<CustomerAccountEntity> accounts = CustomerAccountEntity.list("customerId", customerId);
        for (CustomerAccountEntity account : accounts) {
            CustomerSessionEntity.delete("accountId", account.id);
            CustomerAccountTokenEntity.delete("accountId", account.id);
            CustomerLoginEventEntity.delete("accountId", account.id);
            account.delete();
        }
        CustomerLoginEventEntity.delete("customerId", customerId);
        CustomerLoginRequestEntity.delete("customerId", customerId);
    }
}
