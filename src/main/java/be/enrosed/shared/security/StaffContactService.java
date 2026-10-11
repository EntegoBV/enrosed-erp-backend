package be.enrosed.shared.security;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Persistent, editable contact details for the currently configured staff accounts. */
@ApplicationScoped
public class StaffContactService {

    @ApplicationScoped
    public static class Store implements PanacheRepositoryBase<StaffContactEntity, String> {}

    private final Store store;
    private final List<AdminIdentityProvider.ConfiguredAccount> accounts;

    public StaffContactService(Store store,
                               @ConfigProperty(name = "enrosed.admin.accounts") String configuredAccounts) {
        this.store = store;
        this.accounts = AdminIdentityProvider.parseAccounts(configuredAccounts);
    }

    public List<Contact> list() {
        Map<String, StaffContactEntity> stored = store.listAll().stream()
                .collect(Collectors.toMap(entity -> entity.username, entity -> entity));
        return accounts.stream().map(account -> {
            StaffContactEntity entity = stored.get(account.username());
            return new Contact(account.username(), account.displayName(), entity == null ? null : entity.phone);
        }).toList();
    }

    public List<Contact> catalogContacts() {
        return list().stream().filter(contact -> contact.phone() != null && !contact.phone().isBlank()).toList();
    }

    public String phoneFor(String username) {
        AdminIdentityProvider.ConfiguredAccount account = account(username);
        StaffContactEntity entity = store.findById(account.username());
        return entity == null ? null : entity.phone;
    }

    @Transactional
    public Contact savePhone(String username, String phone) {
        AdminIdentityProvider.ConfiguredAccount account = account(username);
        String normalizedPhone = normalizePhone(phone);
        StaffContactEntity entity = store.findById(account.username());
        if (entity == null) {
            entity = new StaffContactEntity();
            entity.username = account.username();
            store.persist(entity);
        }
        entity.phone = normalizedPhone;
        store.flush();
        return new Contact(account.username(), account.displayName(), entity.phone);
    }

    private AdminIdentityProvider.ConfiguredAccount account(String username) {
        String canonical = ActorRef.canonicalUsername(username);
        return accounts.stream().filter(account -> account.username().equals(canonical)).findFirst()
                .orElseThrow(() -> new NotFoundException("Onbekend medewerkersaccount"));
    }

    static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String normalized = phone.strip();
        long digits = normalized.chars().filter(character -> character >= '0' && character <= '9').count();
        if (normalized.length() > 50 || !normalized.matches("\\+?[0-9 ().-]+") || digits < 7 || digits > 15) {
            throw new BadRequestException("Vul een geldig telefoonnummer in met 7 tot 15 cijfers (maximaal 50 tekens)");
        }
        return normalized;
    }

    public record Contact(String username, String displayName, String phone) {}
}
