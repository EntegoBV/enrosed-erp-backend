package be.enrosed.planning;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;

/**
 * The 2026 fair planning, written into the agenda once.
 *
 * Idempotent on (title, date): rerunning a deploy never duplicates a
 * line, and an item the user deleted by hand stays deleted only until
 * the next boot would re-add it - so titles are specific enough that
 * hand-made items never collide.
 */
@ApplicationScoped
public class BeursplanningSeed {

    private static final Logger LOG = Logger.getLogger(BeursplanningSeed.class);

    private static final String AALSMEER_PRIJS = "Stand 6 × 2 m (12 m²). Standaardbouw € 3.060 + € 175 inschrijving = € 3.235; "
            + "eigen standbouw € 2.940 + € 175 = € 3.115; verschil € 120. Tapijt, meubels en podiums zijn extra. "
            + "Prijzen volgens ontvangen info; btw en extra bestellingen apart controleren.";
    private static final String FLOREDA_PRIJS = "Kale standruimte € 95 per m²: 10 m² € 950 · 20 m² € 1.900 · 30 m² € 2.850 · "
            + "40 m² € 3.800. Parkeren, één stroompunt en catering inbegrepen. Btw en extra bestellingen apart controleren.";

    record Seed(String title, LocalDate onDate, String atTime, String note) {}

    private static final Seed[] PLAN = {
        new Seed("Aalsmeer · standbouw, tapijt en meubilair definitief", LocalDate.of(2026, 9, 22), null, AALSMEER_PRIJS),
        new Seed("Aalsmeer · branding aanleveren", LocalDate.of(2026, 9, 28), null, null),
        new Seed("Aalsmeer · producten, QR/B2B-materiaal en transport voorbereiden", LocalDate.of(2026, 10, 1), null,
                "Loopt in de loop van oktober."),
        new Seed("Aalsmeer · opbouw", LocalDate.of(2026, 11, 1), null, "Opbouwdagen 1 en 2 november."),
        new Seed("Aalsmeer · opbouw (dag 2)", LocalDate.of(2026, 11, 2), null, null),
        new Seed("Aalsmeer · beurs", LocalDate.of(2026, 11, 3), "09:00", "Open 09:00–17:00."),
        new Seed("Aalsmeer · beurs", LocalDate.of(2026, 11, 4), "09:00", "Open 09:00–17:00."),
        new Seed("Aalsmeer · beurs (laatste dag)", LocalDate.of(2026, 11, 5), "09:00",
                "Open 09:00–17:00; na sluiting stand leeghalen."),
        new Seed("Floréda · open stand, meubilair, producten en branding regelen", LocalDate.of(2026, 9, 1), null,
                "Regelen in augustus/september. " + FLOREDA_PRIJS),
        new Seed("Floréda · alle praktische gegevens bevestigen", LocalDate.of(2026, 10, 1), null, FLOREDA_PRIJS),
        new Seed("Floréda · producten en materiaal inpakken", LocalDate.of(2026, 10, 2), null, null),
        new Seed("Floréda · voorlopige opbouw", LocalDate.of(2026, 10, 3), null, null),
        new Seed("Floréda · beurs", LocalDate.of(2026, 10, 4), "10:00", "Open 10:00–17:00."),
        new Seed("Floréda · beurs", LocalDate.of(2026, 10, 5), "08:00", "Open 08:00–21:00."),
        new Seed("Floréda · beurs (laatste dag)", LocalDate.of(2026, 10, 6), "08:00",
                "Open 08:00–13:00, daarna voorlopige afbouw."),
    };

    @Transactional
    void onStart(@Observes StartupEvent event) {
        int added = 0;
        for (Seed seed : PLAN) {
            long existing = PlannerItemEntity.count("title = ?1 and onDate = ?2", seed.title(), seed.onDate());
            if (existing > 0) continue;
            PlannerItemEntity entity = new PlannerItemEntity();
            entity.kind = PlannerItemEntity.Kind.EVENT;
            entity.title = seed.title();
            entity.onDate = seed.onDate();
            entity.atTime = seed.atTime();
            entity.note = seed.note();
            entity.persist();
            added++;
        }
        if (added > 0) LOG.infof("Beursplanning 2026: %d agendapunt(en) toegevoegd", added);
    }
}
