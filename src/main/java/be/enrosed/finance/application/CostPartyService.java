package be.enrosed.finance.application;

import be.enrosed.finance.domain.CostParty;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

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
    /** The one category whose channel is a default worth remembering: the fair itself. */
    public static final String FAIR_CATEGORY = "BEURS";
    private static final Logger LOG = Logger.getLogger(CostPartyService.class);
    private static final int NAME_LENGTH = 200;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern IBAN = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$");
    /** A Belgian VAT number: BE, the leading 0 or 1, nine digits; any other: two letters, then up to sixteen characters. */
    private static final Pattern BELGIAN_VAT = Pattern.compile("^BE[01]\\d{9}$");
    private static final Pattern FOREIGN_VAT = Pattern.compile("^[A-Z]{2}[A-Z0-9]{2,16}$");

    private final CostParties parties;

    @Inject
    Instance<ActivityLogService> activity;
    @Inject CurrentActor currentActor;

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

    /** A rename runs through every booking whose spelling has the old name's key, and says how many of each kind followed. */
    @Transactional
    public CostParty update(long id, CostParty changes) {
        parties.lock(id);
        requireActive(id);
        CostParty current = get(id);
        CostParty clean = validated(changes, current.id(), current.createdAt());
        refuseDuplicate(clean, current.id());
        CostParty saved = parties.save(clean);
        if (saved.name().equals(current.name())) {
            recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Aan wie bijgewerkt");
        } else {
            CostParties.Renamed renamed = parties.renameEverywhere(current.name(), saved.name());
            recordActivity(ActivityLogService.ACTION_UPDATED, saved, "Aan wie hernoemd: " + current.name() + " → " + saved.name()
                    + " · " + renamed.costs() + " kosten · " + renamed.recurringCosts() + " vaste kosten · " + renamed.bankLines() + " bankbewegingen");
        }
        return saved;
    }

    @Transactional
    public void delete(long id) {
        parties.lock(id);
        requireActive(id);
        CostParty party = get(id);
        requireNoAliases(id);
        parties.deleteById(id);
        recordActivity(ActivityLogService.ACTION_DELETED, party, "Uit de lijst Aan wie gehaald; de boekingen blijven staan");
    }

    public List<CostParties.Alias> aliases(long id) {
        get(id);
        return parties.aliases(id);
    }

    /** Only the name-list relationship changes; source rows and all financial records stay intact. */
    @Transactional
    public CostParty merge(long sourceId, long targetId) {
        if (sourceId == targetId) throw new BusinessRuleException("Kies een andere naam om mee samen te voegen");
        parties.lock(sourceId, targetId);
        CostParty source = get(sourceId);
        CostParty target = get(targetId);
        requireActive(targetId);
        Long mergedInto = parties.mergedInto(sourceId);
        if (Long.valueOf(targetId).equals(mergedInto)) return target;
        requireActive(sourceId);
        requireNoAliases(sourceId);
        if (source.vatNumber() != null && target.vatNumber() != null && !source.vatNumber().equals(target.vatNumber())) {
            throw new BusinessRuleException("Deze namen hebben verschillende btw-nummers; samenvoegen kan niet");
        }
        parties.setMergedInto(sourceId, targetId, Instant.now(), currentActor.name());
        recordActivity(ActivityLogService.ACTION_UPDATED, source,
                "Aan wie samengevoegd met " + target.name() + " (#" + targetId + "); oorspronkelijke naam en boekingen bewaard");
        return get(targetId);
    }

    /** Restores the untouched original party row; no costs, documents or payments need to be reconstructed. */
    @Transactional
    public CostParty restore(long sourceId) {
        Long targetId = parties.mergedInto(sourceId);
        if (targetId == null) {
            parties.lock(sourceId);
        } else {
            parties.lock(sourceId, targetId);
        }
        CostParty source = get(sourceId);
        if (parties.mergedInto(sourceId) == null) return source;
        parties.setMergedInto(sourceId, null, null, null);
        recordActivity(ActivityLogService.ACTION_UPDATED, source, "Samengevoegde naam hersteld in de lijst Aan wie; boekingen behouden");
        return get(sourceId);
    }

    private void requireActive(long id) {
        if (parties.mergedInto(id) != null) throw new BusinessRuleException("Herstel deze samengevoegde naam voordat je hem bewerkt");
    }

    private void requireNoAliases(long id) {
        if (!parties.aliases(id).isEmpty()) {
            throw new BusinessRuleException("Herstel eerst de samengevoegde namen bij deze partij");
        }
    }

    /**
     * Notes the name a booking used: an unknown name joins the list with
     * that booking's defaults, a known one is left exactly as it is, with
     * one exception: a known party without a VAT regime takes the regime
     * (and, when it has none, the country) of a confirmed booking, once,
     * so the next cost to it fills itself in. Runs in a transaction of its
     * own and swallows the race of two first bookings of one name, so the
     * booking that called it never fails over the list. A name the list
     * cannot hold (blank, over 200 characters) is let go.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void learn(String name, String category, BigDecimal vatPct, String salesChannel, String country, VatRegime vatRegime) {
        String cleaned = clean(name);
        if (cleaned == null || cleaned.length() > NAME_LENGTH) return;
        String key = CostParty.keyOf(cleaned);
        if (key == null) return;
        CostParty known = parties.findByKey(key).orElse(null);
        if (known != null) {
            if (known.vatRegime() != null || vatRegime == null) return;
            parties.save(validated(new CostParty(known.id(), known.name(), known.category(), known.vatPct(), known.salesChannel(),
                    known.iban(), known.country() == null ? country : known.country(), known.vatNumber(), vatRegime,
                    known.createdAt()), known.id(), known.createdAt()));
            return;
        }
        try {
            parties.save(validated(new CostParty(null, cleaned, category, vatPct, salesChannel, null, country, null, vatRegime, null),
                    null, Instant.now()));
        } catch (PersistenceException race) {
            /* Another booking of the same new name got there first; the list has it either way. */
            LOG.debugf(race, "Aan wie %s was intussen al toegevoegd", cleaned);
        }
    }

    private void refuseDuplicate(CostParty clean, Long ownId) {
        if (parties.isMergedKey(CostParty.keyOf(clean.name()))) {
            throw new BusinessRuleException("Deze naam is al samengevoegd; herstel hem bij de bewaarde partij");
        }
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
        return new CostParty(id, name, upper(party.category()), party.vatPct(), upper(party.salesChannel()),
                iban(party.iban()), country(party.country()), vatNumber(party.vatNumber()), party.vatRegime(),
                createdAt == null ? Instant.now() : createdAt);
    }

    /** Two upper-case letters, the ISO code; null for none. */
    private static String country(String value) {
        String code = upper(value);
        if (code == null) return null;
        if (!code.matches("[A-Z]{2}")) throw new BusinessRuleException("De landcode bestaat uit twee letters");
        return code;
    }

    /** Spaces and dots out, upper-case, and the shape of a VAT number: BE0123456749, NL858617262B02, IE6388047V. */
    private static String vatNumber(String value) {
        if (value == null) return null;
        String number = value.replaceAll("[\\s.]+", "").toUpperCase(Locale.ROOT);
        if (number.isEmpty()) return null;
        boolean valid = number.startsWith("BE") ? BELGIAN_VAT.matcher(number).matches() : FOREIGN_VAT.matcher(number).matches();
        if (!valid || number.length() > 20) throw new BusinessRuleException("Dit is geen geldig btw-nummer");
        return number;
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
