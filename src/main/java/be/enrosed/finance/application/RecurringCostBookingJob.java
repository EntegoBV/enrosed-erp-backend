package be.enrosed.finance.application;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.time.LocalDate;
import java.time.ZoneId;

/** Books the recurring costs that fell due: at start-up, then every hour. */
@ApplicationScoped
public class RecurringCostBookingJob {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    private final RecurringCostService recurring;

    public RecurringCostBookingJob(RecurringCostService recurring) {
        this.recurring = recurring;
    }

    void onStart(@Observes StartupEvent event) {
        book();
    }

    @Scheduled(every = "${enrosed.finance.recurring.every:1h}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void book() {
        recurring.bookDue(LocalDate.now(BRUSSELS));
    }
}
