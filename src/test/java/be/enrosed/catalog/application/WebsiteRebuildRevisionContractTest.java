package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.WebsiteRebuildEntity;
import be.enrosed.catalog.domain.WebsiteRebuildStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebsiteRebuildRevisionContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsExactWebsiteCatalogRevisionContract() throws Exception {
        String revision = "A".repeat(64);
        var payload = JSON.readTree("""
                {
                  "catalogRevision": "%s",
                  "siteCopyRevision": {"en":"17","nl":"18"},
                  "locales": {"en":{"catalogRevision":"%s"}},
                  "builtAt": "2026-08-21T18:50:32.081Z"
                }
                """.formatted(revision, revision));
        assertEquals("a".repeat(64), WebsiteRebuildService.catalogRevision(payload));
    }

    @Test
    void legacyOrPerLocaleRevisionFieldsCannotProveLiveState() throws Exception {
        assertNull(WebsiteRebuildService.catalogRevision(JSON.readTree("""
                {"revision":"%s","currentRevision":"%s","siteCopyRevision":{"en":"17"}}
                """.formatted("a".repeat(64), "b".repeat(64)))));
        assertNull(WebsiteRebuildService.catalogRevision(JSON.readTree(
                "{\"catalogRevision\":\"not-a-sha\"}")));
        assertNull(WebsiteRebuildService.catalogRevision(JSON.readTree(
                "{\"catalogRevision\":{\"en\":\"" + "a".repeat(64) + "\"}}")));
    }

    @Test
    void acceptedButNeverLiveBuildsKeepTheirBoundedAttemptCount() {
        WebsiteRebuildEntity state = new WebsiteRebuildEntity();
        state.status = WebsiteRebuildStatus.QUEUED;
        state.attemptCount = 5;
        Instant acceptedAt = Instant.parse("2026-08-21T12:00:00Z");

        WebsiteRebuildService.applyAccepted(
                state, "a".repeat(64), acceptedAt, true);
        assertEquals(5, state.attemptCount,
                "hook acceptance is not proof that the website is live");
        assertEquals(WebsiteRebuildStatus.TRIGGERED, state.status);

        WebsiteRebuildService.applyLive(state, acceptedAt.plusSeconds(60));
        assertEquals(0, state.attemptCount);
        assertEquals(WebsiteRebuildStatus.LIVE, state.status);
    }

    @Test
    void restartDoesNotResetAnAcceptedButNeverLiveDeliveryCycle() {
        WebsiteRebuildEntity state = new WebsiteRebuildEntity();
        state.status = WebsiteRebuildStatus.TRIGGERED;
        state.currentRevision = "a".repeat(64);
        state.liveRevision = "b".repeat(64);
        state.attemptCount = 5;
        state.hookAcceptedAt = Instant.parse("2026-08-21T12:00:00Z");

        WebsiteRebuildService.applyStartupState(
                state, state.currentRevision, state.hookAcceptedAt.plusSeconds(30));

        assertEquals(WebsiteRebuildStatus.TRIGGERED, state.status);
        assertEquals(5, state.attemptCount);
        assertEquals(Instant.parse("2026-08-21T12:00:00Z"), state.hookAcceptedAt);
    }

    @Test
    void startupQueuesAGenuinelyNewRevisionAsANewDeliveryCycle() {
        WebsiteRebuildEntity state = new WebsiteRebuildEntity();
        state.status = WebsiteRebuildStatus.FAILED_OR_STALE;
        state.currentRevision = "a".repeat(64);
        state.liveRevision = "b".repeat(64);
        state.attemptCount = 5;
        Instant now = Instant.parse("2026-08-21T12:00:00Z");

        WebsiteRebuildService.applyStartupState(state, "c".repeat(64), now);

        assertEquals(WebsiteRebuildStatus.QUEUED, state.status);
        assertEquals("c".repeat(64), state.currentRevision);
        assertEquals(0, state.attemptCount);
        assertEquals(now.plusSeconds(30), state.nextAttemptAt);
    }

    @Test
    void noOpSaveCannotResetTheRetryCeilingForTheSamePublicRevision() {
        WebsiteRebuildEntity state = new WebsiteRebuildEntity();
        state.status = WebsiteRebuildStatus.FAILED_OR_STALE;
        state.currentRevision = "a".repeat(64);
        state.liveRevision = "b".repeat(64);
        state.attemptCount = 5;
        state.queuedAt = Instant.parse("2026-08-21T11:00:00Z");
        state.lastError = "Website draait nog niet op de actuele catalogusrevisie";
        Instant now = Instant.parse("2026-08-21T12:00:00Z");

        boolean queued = WebsiteRebuildService.applyQueuedRevision(
                state, state.currentRevision, now, Duration.ofSeconds(30));

        assertEquals(false, queued);
        assertEquals(WebsiteRebuildStatus.FAILED_OR_STALE, state.status);
        assertEquals(5, state.attemptCount);
        assertEquals(Instant.parse("2026-08-21T11:00:00Z"), state.queuedAt);
        assertEquals("Website draait nog niet op de actuele catalogusrevisie", state.lastError);
    }

    @Test
    void genuinelyNewPublicRevisionStartsOneFreshDeliveryCycle() {
        WebsiteRebuildEntity state = new WebsiteRebuildEntity();
        state.status = WebsiteRebuildStatus.FAILED_OR_STALE;
        state.currentRevision = "a".repeat(64);
        state.liveRevision = "b".repeat(64);
        state.attemptCount = 5;
        state.hookAcceptedAt = Instant.parse("2026-08-21T11:00:00Z");
        Instant now = Instant.parse("2026-08-21T12:00:00Z");

        boolean queued = WebsiteRebuildService.applyQueuedRevision(
                state, "c".repeat(64), now, Duration.ofSeconds(30));

        assertEquals(true, queued);
        assertEquals(WebsiteRebuildStatus.QUEUED, state.status);
        assertEquals("c".repeat(64), state.currentRevision);
        assertEquals(0, state.attemptCount);
        assertEquals(now, state.queuedAt);
        assertEquals(now.plusSeconds(30), state.nextAttemptAt);
        assertNull(state.hookAcceptedAt);
    }
}
