package be.enrosed.finance.application;

import be.enrosed.finance.domain.CostParty;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The list "Aan wie": who the company pays. Filled by hand and by the
 * bookings themselves, which add a name they do not know yet with that
 * booking's defaults and never touch a name they do. Renaming a party
 * renames it on the bookings; deleting one leaves them alone.
 */
@ApplicationScoped
public class CostPartyService {

    public static final String ACTIVITY_ENTITY = "COST_PARTY";
    private static final int NAME_LENGTH = 200;
    private static final int NOTES_LENGTH = 2000;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern IBAN = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$");

    private final CostParties parties;

    @Inject
    Instance<ActivityLogService> activity;

    public CostPartyService(CostParties parties) {
        this.parties = parties;
    }

    public List<CostParty> list() {
        return parties.findAll();
    }

    public CostParty get(long id) {
        return parties.findById(id).orElseThrow(() -> new NotFoundException("Aan wie", id));
    }

    @Transactional
    public CostParty create(CostParty party) {
        CostParty clean = validated(party, null, Instant.now());
        refuseDuplicate(clean, null);
        CostParty saved = parties.save(clean);
        recordActivity(ActivityLogService.ACTION_CREATED, saved, "Toegevoegd aan de lijst Aan wie");
        return saved;
    }

    @Transactional
    public CostParty update(long id, CostParty changes) {
        CostParty current = get(id);
        CostParty clean = validated(changes, current.id(), current.createdAt());
        refuseDuplicate(clean, current.id());
        CostParty saved = parties.save(clean);
        if (saved.name().equals(current.name())) {
            recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Aan wie bijgewerkt");
        } else {
            int touched = parties.renameEverywhere(current.name(), saved.name());
            recordActivity(ActivityLogService.ACTION_UPDATED, saved,
                    "Hernoemd van " + current.name() + " · " + touched + " boeking(en) mee aangepast");
        }
        return saved;
    }

    @Transactional
    public void delete(long id) {
        CostParty party = get(id);
        parties.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, party, "Uit de lijst Aan wie gehaald; de boekingen blijven staan");
    }

    /**
     * Notes the name a booking used: an unknown name joins the list with
     * that booking's defaults, a known one is left exactly as it is.
     */
    @Transactional
    public void learn(String name, String category, BigDecimal vatPct, String salesChannel) {
        String key = CostParty.keyOf(name);
        if (key == null || key.length() > NAME_LENGTH || parties.findByKey(key).isPresent()) return;
        parties.save(validated(new CostParty(null, name, category, vatPct, salesChannel, null, null, null), null, Instant.now()));
    }

    private void refuseDuplicate(CostParty clean, Long ownId) {
        parties.findByKey(CostParty.keyOf(clean.name()))
                .filter(other -> ownId == null || !ownId.equals(other.id()))
                .ifPresent(other -> {
                    throw new BusinessRuleException("Deze naam staat al in de lijst Aan wie");
                });
    }

    private static CostParty validated(CostParty party, Long id, Instant createdAt) {
        if (party == null) throw new BusinessRuleException("Geen gegevens meegestuurd");
        String name = clean(party.name());
        if (name == null) throw new BusinessRuleException("Geef een naam");
        if (name.length() > NAME_LENGTH) throw new BusinessRuleException("De naam is maximaal 200 tekens");
        if (party.vatPct() != null && (party.vatPct().signum() < 0 || party.vatPct().compareTo(HUNDRED) > 0)) {
            throw new BusinessRuleException("Het btw-percentage ligt tussen 0 en 100");
        }
        String notes = clean(party.notes());
        if (notes != null && notes.length() > NOTES_LENGTH) throw new BusinessRuleException("De notities zijn maximaal 2000 tekens");
        return new CostParty(id, name, upper(party.category()), party.vatPct(), upper(party.salesChannel()),
                iban(party.iban()), notes, createdAt == null ? Instant.now() : createdAt);
    }

    /** Spaces out, upper-case, and the shape of an IBAN: two letters, two check digits, the national part. */
    private static String iban(String value) {
        if (value == null) return null;
        String iban = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (iban.isEmpty()) return null;
        if (!IBAN.matcher(iban).matches()) throw new BusinessRuleException("Dit is geen geldig IBAN");
        return iban;
    }

    private static String upper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void recordActivity(String action, CostParty party, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY, party.id() == null ? null : party.id().toString(), party.name(), summary);
    }
}
