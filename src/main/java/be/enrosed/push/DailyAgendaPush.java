package be.enrosed.push;

import be.enrosed.planning.PlannerItemEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The day's agenda as one morning notification.
 *
 * Deliberately at 09:00 Brussels time - a calendar that buzzes at midnight
 * wakes people for nothing. Nothing planned means no notification at all.
 */
@ApplicationScoped
public class DailyAgendaPush {

    private static final Logger LOG = Logger.getLogger(DailyAgendaPush.class);

    private final WebPushNotifier push;
    private final jakarta.persistence.EntityManager entities;

    public DailyAgendaPush(WebPushNotifier push, jakarta.persistence.EntityManager entities) {
        this.push = push;
        this.entities = entities;
    }

    @Scheduled(cron = "0 0 9 * * ?", timeZone = "Europe/Brussels")
    @Transactional
    public void morningDigest() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/Brussels"));
        List<PlannerItemEntity> planned = PlannerItemEntity
                .<PlannerItemEntity>list("onDate = ?1 and done = false order by atTime nulls last",
                        today);
        long overdueInvoices = entities.createQuery(
                        "select count(o) from SalesOrderEntity o where o.docType = :sort"
                        + " and o.status = :status and o.invoiceDueDate < :today", Long.class)
                .setParameter("sort", be.enrosed.sales.domain.DocumentType.FACTUUR)
                .setParameter("status", be.enrosed.sales.domain.QuoteStatus.VERZONDEN)
                .setParameter("today", today)
                .getSingleResult();
        if (planned.isEmpty() && overdueInvoices == 0) return;

        String body = planned.stream()
                .limit(5)
                .map(item -> (item.atTime == null || item.atTime.isBlank()
                        ? "" : item.atTime + " · ") + item.title)
                .collect(Collectors.joining("\n"));
        if (planned.size() > 5) body += "\n+ " + (planned.size() - 5) + " meer";
        if (overdueInvoices > 0) {
            body = (body.isBlank() ? "" : body + "\n")
                    + "! " + overdueInvoices + " factuur/facturen vervallen - betaling opvolgen";
        }

        String title = planned.isEmpty()
                ? "\u23F0 Vervallen facturen opvolgen"
                : planned.size() == 1
                        ? "\u2600\uFE0F Vandaag gepland: " + planned.get(0).title
                        : "\u2600\uFE0F Vandaag " + planned.size() + " punten in de agenda";
        push.notifyAll("agenda", title, body, "/");
        LOG.infof("Ochtendagenda gepusht: %d punt(en)", planned.size());
    }
}
