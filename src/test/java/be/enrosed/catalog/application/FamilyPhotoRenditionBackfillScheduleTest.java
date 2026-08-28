package be.enrosed.catalog.application;

import io.quarkus.scheduler.Scheduled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FamilyPhotoRenditionBackfillScheduleTest {

    @Test
    void boundedWorkerHasAConfigurableNonConcurrentSchedule() throws Exception {
        Scheduled schedule = FamilyPhotoRenditionBackfillService.class
                .getDeclaredMethod("work")
                .getAnnotation(Scheduled.class);

        assertEquals("catalog-family-photo-rendition-backfill", schedule.identity());
        assertEquals("{enrosed.catalog.photo-rendition.cron}", schedule.cron());
        assertEquals(Scheduled.ConcurrentExecution.SKIP, schedule.concurrentExecution());
        assertEquals(8, FamilyPhotoRenditionBackfillService.BATCH_SIZE);
    }
}
