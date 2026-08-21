package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.in.rest.WebsiteRebuildDto;
import be.enrosed.catalog.adapter.out.persistence.CanonicalCatalogDaos;
import be.enrosed.catalog.adapter.out.persistence.WebsiteRebuildEntity;
import be.enrosed.catalog.domain.WebsiteRebuildStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable, debounced Vercel deploy-hook integration; URLs are never returned or logged. */
@ApplicationScoped
public class WebsiteRebuildService {
    private static final Duration DEBOUNCE = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STALE_AFTER = Duration.ofMinutes(20);
    private static final java.util.regex.Pattern SHA256 =
            java.util.regex.Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final CanonicalCatalogDaos.WebsiteRebuilds rows;
    private final WebsiteCatalogRevisionService revisions;
    private final ObjectMapper json;

    @ConfigProperty(name = "VERCEL_WEBSITE_DEPLOY_HOOK_URL")
    Optional<String> deployHookUrl;

    @ConfigProperty(name = "WEBSITE_PUBLIC_REVISION_URL")
    Optional<String> publicRevisionUrl;

    public WebsiteRebuildService(
            CanonicalCatalogDaos.WebsiteRebuilds rows,
            WebsiteCatalogRevisionService revisions,
            ObjectMapper json) {
        this.rows = rows;
        this.revisions = revisions;
        this.json = json;
    }

    /** Called inside the owning mutation transaction, making this a real transactional outbox. */
    @Transactional
    public void queue() {
        if (!configured(deployHookUrl)) return;
        WebsiteRebuildEntity state = lockedState();
        applyQueuedRevision(state, revisions.currentRevision(), Instant.now(), DEBOUNCE);
    }

    @Transactional
    public WebsiteRebuildDto retry() {
        if (!configured(deployHookUrl)) return notConfigured();
        WebsiteRebuildEntity state = lockedState();
        Instant now = Instant.now();
        String current = revisions.currentRevision();
        state.status = WebsiteRebuildStatus.QUEUED;
        state.queuedAt = now;
        state.nextAttemptAt = now;
        state.currentRevision = current;
        state.lastError = null;
        state.attemptCount = 0;
        state.hookAcceptedAt = null;
        return dto(state, current);
    }

    @Transactional
    public WebsiteRebuildDto status() {
        if (!configured(deployHookUrl)) return notConfigured();
        WebsiteRebuildEntity state = state();
        String current = revisions.currentRevision();
        if (state == null) {
            state = lockedState();
            state.status = WebsiteRebuildStatus.QUEUED;
            state.queuedAt = Instant.now();
            state.nextAttemptAt = state.queuedAt;
            state.currentRevision = current;
            rows.flush();
        }
        WebsiteRebuildStatus status = state.status;
        if (Objects.equals(current, state.liveRevision)) status = WebsiteRebuildStatus.LIVE;
        else if (status == WebsiteRebuildStatus.LIVE) status = WebsiteRebuildStatus.FAILED_OR_STALE;
        return dto(state, current, status);
    }

    @Transactional
    void onStart(@Observes StartupEvent ignored) {
        if (!configured(deployHookUrl)) return;
        WebsiteRebuildEntity state = lockedState();
        Instant now = Instant.now();
        String current = revisions.currentRevision();
        applyStartupState(state, current, now);
    }

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void work() {
        if (!configured(deployHookUrl)) return;
        Work claimed = QuarkusTransaction.requiringNew().call(this::claim);
        if (claimed == null) return;
        HookResult result = trigger(claimed.revision());
        QuarkusTransaction.requiringNew().run(() -> finish(claimed, result));
    }

    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollLiveRevision() {
        if (!configured(deployHookUrl) || !configured(publicRevisionUrl)) return;
        PollResult result = pollRevision();
        QuarkusTransaction.requiringNew().run(() -> updateLiveRevision(result));
    }

    private Work claim() {
        WebsiteRebuildEntity state = rows.findById(1L, LockModeType.PESSIMISTIC_WRITE);
        Instant now = Instant.now();
        if (state == null || state.status != WebsiteRebuildStatus.QUEUED
                    && state.status != WebsiteRebuildStatus.FAILED_OR_STALE
                || state.attemptCount >= 5
                || state.nextAttemptAt != null && state.nextAttemptAt.isAfter(now)) return null;
        String revision = revisions.currentRevision();
        state.currentRevision = revision;
        state.lastAttemptAt = now;
        state.attemptCount++;
        /* Reserve long enough that another scheduler node cannot send the same hook. */
        state.nextAttemptAt = now.plus(REQUEST_TIMEOUT).plusSeconds(15);
        rows.flush();
        return new Work(state.queuedAt, revision);
    }

    private HookResult trigger(String revision) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(deployHookUrl.orElseThrow().strip()))
                    .timeout(REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? new HookResult(true, null)
                    : new HookResult(false, "Deploy hook antwoordde met HTTP " + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new HookResult(false, "Deploy hook werd onderbroken");
        } catch (Exception exception) {
            return new HookResult(false, "Deploy hook kon niet bereikt worden ("
                    + exception.getClass().getSimpleName() + ")");
        }
    }

    private void finish(Work claimed, HookResult result) {
        WebsiteRebuildEntity state = rows.findById(1L, LockModeType.PESSIMISTIC_WRITE);
        if (state == null) return;
        Instant now = Instant.now();
        boolean newerQueue = state.queuedAt != null && claimed.queuedAt() != null
                && state.queuedAt.isAfter(claimed.queuedAt());
        if (result.accepted()) {
            if (!newerQueue) applyAccepted(
                    state, claimed.revision(), now, configured(publicRevisionUrl));
        } else if (!newerQueue) {
            state.status = WebsiteRebuildStatus.FAILED_OR_STALE;
            state.lastError = result.error();
            state.nextAttemptAt = state.attemptCount >= 5 ? null
                    : now.plusSeconds(Math.min(900, 15L << Math.min(5, state.attemptCount)));
        }
    }

    private PollResult pollRevision() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(publicRevisionUrl.orElseThrow().strip()))
                    .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new PollResult(null,
                        "Publieke revisie antwoordde met HTTP " + response.statusCode());
            }
            JsonNode payload = json.readTree(response.body());
            String revision = catalogRevision(payload);
            return revision == null
                    ? new PollResult(null,
                            "Publieke revisie bevat geen geldige catalogRevision (SHA-256)")
                    : new PollResult(revision, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new PollResult(null, "Publieke revisiecontrole werd onderbroken");
        } catch (Exception exception) {
            return new PollResult(null, "Publieke revisie kon niet gelezen worden ("
                    + exception.getClass().getSimpleName() + ")");
        }
    }

    private void updateLiveRevision(PollResult result) {
        WebsiteRebuildEntity state = rows.findById(1L, LockModeType.PESSIMISTIC_WRITE);
        if (state == null) return;
        Instant now = Instant.now();
        if (result.revision() != null) {
            state.liveRevision = result.revision();
            if (Objects.equals(state.currentRevision, result.revision())) {
                applyLive(state, now);
            } else if (state.hookAcceptedAt != null
                    && state.hookAcceptedAt.plus(STALE_AFTER).isBefore(now)) {
                state.status = WebsiteRebuildStatus.FAILED_OR_STALE;
                state.lastError = "Website draait nog niet op de actuele catalogusrevisie";
            }
        } else if (state.hookAcceptedAt != null
                && state.hookAcceptedAt.plus(STALE_AFTER).isBefore(now)) {
            state.status = WebsiteRebuildStatus.FAILED_OR_STALE;
            state.lastError = result.error();
        }
    }

    private WebsiteRebuildEntity lockedState() {
        WebsiteRebuildEntity state = rows.findById(1L, LockModeType.PESSIMISTIC_WRITE);
        if (state == null) {
            ensureStateRow();
            state = rows.findById(1L, LockModeType.PESSIMISTIC_WRITE);
            if (state == null) throw new IllegalStateException(
                    "Website-rebuildstatus kon niet atomair aangemaakt worden");
        }
        return state;
    }

    /** Conflict-safe singleton creation; ordinary editor transactions never need a global lock. */
    private void ensureStateRow() {
        String database = rows.getEntityManager().unwrap(org.hibernate.Session.class)
                .doReturningWork(connection -> connection.getMetaData().getDatabaseProductName());
        String sql = database != null
                && database.toLowerCase(java.util.Locale.ROOT).contains("postgresql")
                ? "insert into website_rebuild "
                    + "(id, rowRevision, status, attemptCount) "
                    + "values (1, 0, 'QUEUED', 0) on conflict (id) do nothing"
                : "merge into website_rebuild "
                    + "(id, rowRevision, status, attemptCount) key(id) "
                    + "values (1, 0, 'QUEUED', 0)";
        rows.getEntityManager().createNativeQuery(sql).executeUpdate();
        rows.flush();
    }

    static void applyAccepted(
            WebsiteRebuildEntity state, String revision, Instant now, boolean pollConfigured) {
        state.hookAcceptedAt = now;
        state.currentRevision = revision;
        state.lastError = null;
        /* Keep the bounded attempt count until this exact revision is observed live. */
        state.status = WebsiteRebuildStatus.TRIGGERED;
        state.nextAttemptAt = pollConfigured ? now.plusSeconds(30) : null;
    }

    static void applyLive(WebsiteRebuildEntity state, Instant now) {
        state.status = WebsiteRebuildStatus.LIVE;
        state.liveAt = now;
        state.lastError = null;
        state.nextAttemptAt = null;
        state.attemptCount = 0;
    }

    static void applyStartupState(
            WebsiteRebuildEntity state, String currentRevision, Instant now) {
        if (Objects.equals(currentRevision, state.liveRevision)) {
            if (state.status != WebsiteRebuildStatus.LIVE) applyLive(state, now);
            return;
        }
        /* A restart is not a new publication attempt. Preserve the delivery ceiling while the
           exact revision is already queued, triggered, or waiting for its bounded retry. */
        if (Objects.equals(currentRevision, state.currentRevision)
                && state.status != WebsiteRebuildStatus.LIVE) {
            if (state.status == WebsiteRebuildStatus.QUEUED
                    && state.nextAttemptAt == null && state.attemptCount < 5) {
                state.nextAttemptAt = now.plus(DEBOUNCE);
            }
            return;
        }
        queueState(state, currentRevision, now, DEBOUNCE);
    }

    /**
     * A normal save only starts a fresh delivery cycle when its public digest changed.
     * Explicit retry remains the sole way to reset a failed cycle for the same revision.
     */
    static boolean applyQueuedRevision(
            WebsiteRebuildEntity state, String revision, Instant now, Duration delay) {
        if (Objects.equals(revision, state.liveRevision)) {
            if (state.status != WebsiteRebuildStatus.LIVE) applyLive(state, now);
            state.currentRevision = revision;
            return false;
        }
        if (Objects.equals(revision, state.currentRevision)) return false;
        queueState(state, revision, now, delay);
        return true;
    }

    private static void queueState(
            WebsiteRebuildEntity state, String revision, Instant now, Duration delay) {
        state.status = WebsiteRebuildStatus.QUEUED;
        state.queuedAt = now;
        state.nextAttemptAt = now.plus(delay);
        state.currentRevision = revision;
        state.lastError = null;
        state.attemptCount = 0;
        state.hookAcceptedAt = null;
    }

    private WebsiteRebuildEntity state() { return rows.findById(1L); }

    private WebsiteRebuildDto dto(WebsiteRebuildEntity state, String current) {
        return dto(state, current, state.status);
    }

    private static WebsiteRebuildDto dto(
            WebsiteRebuildEntity state, String current, WebsiteRebuildStatus status) {
        return new WebsiteRebuildDto(status, state.queuedAt, state.lastAttemptAt,
                state.hookAcceptedAt, state.liveAt, state.nextAttemptAt,
                current, state.liveRevision, state.lastError);
    }

    private static WebsiteRebuildDto notConfigured() {
        return new WebsiteRebuildDto(WebsiteRebuildStatus.NOT_CONFIGURED,
                null, null, null, null, null, null, null, null);
    }

    static String catalogRevision(JsonNode payload) {
        JsonNode value = payload == null ? null : payload.get("catalogRevision");
        if (value == null || !value.isTextual()) return null;
        String revision = value.asText().strip();
        return SHA256.matcher(revision).matches()
                ? revision.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static boolean configured(Optional<String> value) {
        return value != null && value.filter(candidate -> !candidate.isBlank()).isPresent();
    }
    private record Work(Instant queuedAt, String revision) {}
    private record HookResult(boolean accepted, String error) {}
    private record PollResult(String revision, String error) {}
}
