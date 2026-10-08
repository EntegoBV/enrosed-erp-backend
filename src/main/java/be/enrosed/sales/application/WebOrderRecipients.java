package be.enrosed.sales.application;

import be.enrosed.account.CustomerAccountEntity;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Language;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.Objects;

/**
 * Whom a customer mail about a document reaches. A company can have several
 * logins whose address differs from the customer record's: the person who
 * placed a website order must receive what Enrosed writes about it, and the
 * record keeps a copy of what concerns the company's document. Every other
 * document mails the record, as it always did.
 */
@ApplicationScoped
public class WebOrderRecipients {
    /** The login status that may receive mail; a plain string in the login table. */
    private static final String ACTIVE = "ACTIVE";

    /** cc is the customer record's address when the mail goes to a login with another address; else null. */
    public record Recipient(String to, String cc, Language language) {}

    @Inject WebOrders webOrders;
    @Inject EntityManager entities;

    public Recipient of(SalesOrder order, Customer customer) {
        String recordEmail = customer == null ? null : customer.email();
        Language recordLanguage = customer == null || customer.language() == null ? Language.NL : customer.language();
        WebOrders.Row row = order == null || order.id() == null ? null : webOrders.find(order.id()).orElse(null);
        /* Re-linked by staff to another customer: the row is not this customer's and says nothing about them. */
        if (row == null || row.customerId() == null || !Objects.equals(row.customerId(), order.customerId()))
            return new Recipient(recordEmail, null, recordLanguage);

        Language language = language(row.language(), recordLanguage);
        CustomerAccountEntity login = row.accountId() == null ? null
                : entities.find(CustomerAccountEntity.class, row.accountId());
        boolean reachable = login != null && ACTIVE.equals(login.status)
                && Objects.equals(login.customerId, order.customerId())
                && row.accountEmail() != null && !row.accountEmail().isBlank();
        if (!reachable) return new Recipient(recordEmail, null, language);
        String to = row.accountEmail().strip();
        boolean differs = recordEmail != null && !recordEmail.isBlank() && !recordEmail.strip().equalsIgnoreCase(to);
        return new Recipient(to, differs ? recordEmail.strip() : null, language);
    }

    /** The language of the page the order was last placed or changed on; the record's when that is unknown. */
    private static Language language(String code, Language fallback) {
        try {
            return Language.requireSupported(code, fallback);
        } catch (IllegalArgumentException unsupported) {
            return fallback;
        }
    }
}
