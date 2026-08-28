package be.enrosed.shared;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SchedulerIsolationRuntimeTest.WebsiteOnlySchedulerProfile.class)
class SchedulerIsolationRuntimeTest {

    @Inject Scheduler scheduler;

    @Test
    void enablingTheSchedulerRegistersOnlyTheWebsiteRebuildTimers() {
        assertTrue(scheduler.isStarted());
        Set<String> identities = scheduler.getScheduledJobs().stream()
                .map(Trigger::getId)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "website-rebuild-worker",
                "website-rebuild-live-revision-poller"), identities);
    }

    public static class WebsiteOnlySchedulerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.scheduler.enabled", "true",
                    "enrosed.push.daily-agenda.cron", "off",
                    "enrosed.market.refresh.cron", "off",
                    "enrosed.catalog.photo-rendition.cron", "off");
        }
    }
}
