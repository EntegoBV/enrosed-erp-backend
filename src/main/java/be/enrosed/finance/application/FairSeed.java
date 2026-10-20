package be.enrosed.finance.application;

import be.enrosed.finance.domain.CostParty;
import be.enrosed.finance.domain.Fair;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the lists start with, on every start and without effect the second
 * time: the two fairs the company already stood on; the costs booked on the
 * retired category TICA moved to "Beurs & stand" with TICA as their fair;
 * and every name the bookings already carry in the list "Aan wie" with the
 * newest booking's defaults. The PostgreSQL migration does the same three
 * things in SQL, so either path finds the lists filled.
 */
@ApplicationScoped
public class FairSeed {

    private static final Logger LOG = Logger.getLogger(FairSeed.class);
    private static final String TICA = "TICA";
    private static final ZoneId BANK_ZONE = ZoneId.of("Europe/Brussels");
    private static final int NAME_LENGTH = 200;

    private final Fairs fairs;
    private final CostParties parties;
    private final EntityManager entityManager;

    public FairSeed(Fairs fairs, CostParties parties, EntityManager entityManager) {
        this.fairs = fairs;
        this.parties = parties;
        this.entityManager = entityManager;
    }

    @Transactional
    void onStart(@Observes StartupEvent event) {
        seed(TICA, "TICA");
        seed("EDE", "Ede");
        moveTicaCategory();
        backfillParties();
    }

    private void seed(String code, String label) {
        if (fairs.findByCode(code).isPresent()) return;
        fairs.save(new Fair(null, code, label, true, Instant.now()));
        LOG.infof("Beurs %s (%s) toegevoegd aan de lijst", label, code);
    }

    /** The category TICA is retired: those costs are fair costs with TICA as the fair (unless they named another channel). */
    private void moveTicaCategory() {
        int moved = 0;
        for (String entity : List.of("CompanyCostEntity", "RecurringCostEntity")) {
            entityManager.createQuery("update " + entity + " e set e.salesChannel = :fair where upper(e.category) = :fair and e.salesChannel is null")
                    .setParameter("fair", TICA).executeUpdate();
            moved += entityManager.createQuery("update " + entity + " e set e.category = :category where upper(e.category) = :fair")
                    .setParameter("category", CostPartyService.FAIR_CATEGORY).setParameter("fair", TICA).executeUpdate();
        }
        if (moved > 0) LOG.infof("%d kosten van de categorie TICA naar Beurs & stand gezet", moved);
    }

    /** A booking as the backfill sees it: who, with what defaults, on which day. */
    private record Booking(String name, String category, BigDecimal vatPct, String salesChannel, LocalDate day, long id) {}

    /**
     * Every distinct name the costs, recurring costs and outgoing bank lines
     * carry joins the list once, with the newest booking's defaults and the
     * channel only when that booking was a fair cost. Names already in the
     * list are left alone.
     */
    private void backfillParties() {
        List<Booking> bookings = new ArrayList<>();
        for (Object[] row : entityManager.createQuery(
                "select c.party, c.category, c.vatPct, c.salesChannel, c.date, c.id from CompanyCostEntity c where c.party is not null", Object[].class).getResultList()) {
            bookings.add(new Booking((String) row[0], (String) row[1], (BigDecimal) row[2], (String) row[3], (LocalDate) row[4], (Long) row[5]));
        }
        for (Object[] row : entityManager.createQuery(
                "select r.party, r.category, r.vatPct, r.salesChannel, r.startDate, r.id from RecurringCostEntity r where r.party is not null", Object[].class).getResultList()) {
            bookings.add(new Booking((String) row[0], (String) row[1], (BigDecimal) row[2], (String) row[3], (LocalDate) row[4], (Long) row[5]));
        }
        for (Object[] row : entityManager.createQuery(
                "select b.counterparty, b.bookedAt, b.id from BankStatementLineEntity b where b.counterparty is not null and b.amountEur < 0", Object[].class).getResultList()) {
            bookings.add(new Booking((String) row[0], null, null, null, ((Instant) row[1]).atZone(BANK_ZONE).toLocalDate(), (Long) row[2]));
        }
        bookings.sort(Comparator.comparing(Booking::day).thenComparingLong(Booking::id).reversed());
        Set<String> seen = new HashSet<>();
        int added = 0;
        for (Booking booking : bookings) {
            String name = booking.name().strip();
            String key = CostParty.keyOf(name);
            // findByKey also resolves preserved merged names: bank spellings must not recreate list duplicates.
            if (key == null || name.length() > NAME_LENGTH || !seen.add(key) || parties.findByKey(key).isPresent()) continue;
            String channel = CostPartyService.FAIR_CATEGORY.equals(booking.category()) ? booking.salesChannel() : null;
            parties.save(new CostParty(null, name, booking.category(), booking.vatPct(), channel, null, Instant.now()));
            added++;
        }
        if (added > 0) LOG.infof("%d namen uit de boekingen toegevoegd aan de lijst Aan wie", added);
    }
}
