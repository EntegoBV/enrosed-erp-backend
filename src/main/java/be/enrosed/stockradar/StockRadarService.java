package be.enrosed.stockradar;

import be.enrosed.stockradar.StockRadar.Family;
import be.enrosed.stockradar.StockRadar.FetchFailure;
import be.enrosed.stockradar.StockRadar.ManualShop;
import be.enrosed.stockradar.StockRadar.Observation;
import be.enrosed.stockradar.StockRadar.Page;
import be.enrosed.stockradar.StockRadar.Item;
import be.enrosed.stockradar.StockRadar.Shop;
import be.enrosed.stockradar.StockRadar.Watch;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.stockradar.StockRadarEntities.RecipientEntity;
import be.enrosed.stockradar.StockRadarEntities.WatchStateEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Timing, state and notifications of the stock radar.
 *
 * Every shop is read once a minute (Emre, 2026-11-15: "doe per minuut"), up
 * to 10 % later and never earlier. Apple's one request answers for all four
 * items; Vanden Borre's four pages are read every minute; Coolblue's four
 * items take turns, one page per country a minute, because four a minute
 * brings its robot check within minutes. Every Coolblue page also names the
 * online state of all four: a item that turns InStock there is read at once.
 * A round runs on one background thread: the shops are asked in parallel, the
 * pages of one shop one after the other with a short pause. There is no
 * browser and no child process: the first radar launched Chromium twice a
 * minute and the backend grew ~0.7 GB a day until each redeploy.
 *
 * A watch's "available" verdict is stored per page and item. Becoming
 * available sends a push and a mail; a Coolblue store that joins does too.
 * "Weer weg" needs two reads in a row and is a push only, with the same tag,
 * so on the item it takes the place of the "beschikbaar" banner. A shop that
 * fails is asked again after 2, 4, then at most 5 minutes; one that refuses
 * (403, 429, 503, Apple's 541, a robot check) after 2, 4, 8, then 15 minutes.
 */
@ApplicationScoped
public class StockRadarService {

    private static final Logger LOG = Logger.getLogger(StockRadarService.class);
    static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.of("nl", "BE"));
    /** Every page once a minute: Emre's rhythm, and the pace the shops accept. */
    static final int INTERVAL_SECONDS = 60;
    private static final int MAX_BACKOFF_SECONDS = 300;
    private static final int MAX_REFUSED_BACKOFF_SECONDS = 900;
    /** Two reads of "not available" in a row before "weer weg": one odd page does not ring twice. */
    private static final int GONE_READS = 2;
    private static final long SAME_HOST_PAUSE_MS = 1_500;
    /** Extra mail addresses next to the configured one (Emre, 2026-11-15: "een extra mail naar kunnen zetten"). */
    static final int MAX_EXTRA_RECIPIENTS = 5;
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    /**
     * Unset: on only on Railway's production environment. Railway test runs on a copy of production's devices, and
     * a local run of the prod profile on a restored dump would push to Emre's phone; both stay quiet unless asked.
     */
    @ConfigProperty(name = "enrosed.stockradar.enabled") Optional<Boolean> enabledSetting;
    @ConfigProperty(name = "enrosed.stockradar.push-enabled", defaultValue = "true") boolean pushEnabled;
    @ConfigProperty(name = "enrosed.stockradar.mail-enabled", defaultValue = "true") boolean mailEnabled;
    @ConfigProperty(name = "enrosed.stockradar.notify-user", defaultValue = "emre") String notifyUser;
    @ConfigProperty(name = "enrosed.stockradar.mail-to", defaultValue = "emre@entego.be") String mailTo;
    @ConfigProperty(name = "enrosed.stockradar.max-delivery-days", defaultValue = "3") int maxDeliveryDays;
    /** Multiplies the rhythm: 2.0 halves the traffic. */
    @ConfigProperty(name = "enrosed.stockradar.speed", defaultValue = "1.0") double speed;

    private final StockRadarPushSender push;
    private final StockRadarMailSender mail;
    private final StockRadarHttpFetcher http = new StockRadarHttpFetcher();
    /** Per slot (a page, or a group that takes turns): when it is due. */
    private final Map<String, Instant> nextCheck = new ConcurrentHashMap<>();
    private final Map<String, Instant> slotAsked = new ConcurrentHashMap<>();
    /** Failed reads of a slot in a row; sets its wait. */
    private final Map<String, Integer> slotErrors = new ConcurrentHashMap<>();
    /** Per watch: when its page was last asked, for the turns of a group. */
    private final Map<String, Instant> lastAsked = new ConcurrentHashMap<>();
    /** Per group: the watches that go next, in order (a page asked for again, an iPhone Coolblue showed in stock). */
    private final Map<String, java.util.Deque<String>> promoted = new ConcurrentHashMap<>();
    /** Per host: no page before this moment, after the shop refused (one refusal holds back all its pages). */
    private final Map<String, Instant> hostNotBefore = new ConcurrentHashMap<>();
    /** Per watch: an undelivered alert is tried again from this moment on, every time a little later. */
    private final Map<String, Instant> retryNotBefore = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryCount = new ConcurrentHashMap<>();
    /** Per watch: Coolblue's online state as last seen on any of the group's pages. */
    private final Map<String, String> variantState = new ConcurrentHashMap<>();
    private final AtomicBoolean roundBusy = new AtomicBoolean();
    private final AtomicBoolean greeted = new AtomicBoolean();
    private final AtomicLong rounds = new AtomicLong();
    private final ExecutorService roundThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stock-radar");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Instant lastTickAt;
    private volatile Instant lastRoundAt;
    private volatile long lastRoundMillis;
    private volatile Instant lastSummaryAt;

    public StockRadarService(StockRadarPushSender push, StockRadarMailSender mail) {
        this.push = push;
        this.mail = mail;
    }

    @PostConstruct
    void stagger() {
        Instant now = Instant.now();
        // Each shop starts at its own second, so the shops do not keep asking together.
        Map<Shop, Integer> offsets = new HashMap<>();
        for (Shop shop : StockRadarStores.SHOPS) offsets.put(shop, ThreadLocalRandom.current().nextInt(5, 45));
        for (Watch watch : StockRadarStores.WATCHES) nextCheck.putIfAbsent(watch.slot(), now.plusSeconds(offsets.get(watch.shop())));
        seedWaits(now);
        LOG.infof("Voorraadradar %s: %d pagina's per minuut bij %d winkels, meldingen naar %s (push %s, mail %s naar %s), levering binnen %d dagen",
                enabled() ? "actief" : "uit", slots().stream().map(slot -> pick(slot).stream().map(Watch::url).distinct().count()).mapToLong(Long::longValue).sum(),
                StockRadarStores.SHOPS.size(), notifyUser, pushEnabled && push.enabled(), mailEnabled, mailTo, maxDeliveryDays);
    }

    /**
     * A redeploy does not forget a shop that was refusing: its pages wait out the
     * rest of the wait the stored failures give, as if the radar had not restarted.
     */
    private void seedWaits(Instant now) {
        try {
            Map<String, WatchStateEntity> rows = new HashMap<>();
            QuarkusTransaction.requiringNew().run(() -> {
                for (WatchStateEntity row : WatchStateEntity.<WatchStateEntity>listAll()) rows.put(row.watchKey, row);
            });
            // Per slot: the failures in a row of its pages (a group's pages each count, they all asked the same shop),
            // dated by the latest of them.
            Map<String, Integer> errors = new HashMap<>();
            Map<String, Instant> latest = new HashMap<>();
            Map<String, Boolean> refusedSlot = new HashMap<>();
            for (Watch watch : StockRadarStores.WATCHES) {
                WatchStateEntity row = rows.get(watch.key());
                if (row == null || row.errorCount == null || row.errorCount == 0 || row.checkedAt == null) continue;
                String slot = watch.slot();
                int count = watch.group() == null ? row.errorCount : row.errorCount + errors.getOrDefault(slot, 0);
                errors.put(slot, watch.group() == null ? Math.max(errors.getOrDefault(slot, 0), row.errorCount) : count);
                latest.merge(slot, row.checkedAt, (a, b) -> a.isAfter(b) ? a : b);
                refusedSlot.merge(slot, Boolean.TRUE.equals(row.refused), (a, b) -> a || b);
            }
            for (Map.Entry<String, Integer> entry : errors.entrySet()) {
                String slot = entry.getKey();
                boolean refused = refusedSlot.getOrDefault(slot, false);
                int count = entry.getValue();
                long wait = Math.min(refused ? MAX_REFUSED_BACKOFF_SECONDS : MAX_BACKOFF_SECONDS, (long) interval() * (1L << Math.min(4, count)));
                Instant until = latest.get(slot).plusSeconds(wait);
                if (!until.isAfter(now)) continue;
                slotErrors.merge(slot, count, Math::max);
                nextCheck.merge(slot, until, (a, b) -> a.isAfter(b) ? a : b);
                String url = StockRadarStores.WATCHES.stream().filter(watch -> watch.slot().equals(slot)).map(Watch::url).findFirst().orElse(null);
                if (refused && url != null) hostNotBefore.merge(hostOf(url), until, (a, b) -> a.isAfter(b) ? a : b);
            }
        } catch (RuntimeException failure) {
            LOG.warnf("Voorraadradar: wachttijden niet hersteld (%s)", failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() { roundThread.shutdownNow(); }

    public boolean enabled() {
        return enabledSetting.orElseGet(() -> "production".equalsIgnoreCase(System.getenv("RAILWAY_ENVIRONMENT_NAME")));
    }

    public String notifyUser() { return notifyUser; }

    int interval() { return (int) Math.max(30, Math.round(INTERVAL_SECONDS * (speed <= 0 ? 1.0 : speed))); }

    /** Never earlier than the rhythm: only later, by up to 10 %. */
    private Instant jittered(Instant from) {
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble() * 0.10;
        return from.plusMillis(Math.round(interval() * 1000L * factor));
    }

    // -------------------------------------------------------------------- ticks

    /** The job's heartbeat: starts a round for what is due, unless one still runs. */
    public void tick(Instant now) {
        if (!enabled()) return;
        lastTickAt = now;
        List<Watch> due = new ArrayList<>();
        for (String slot : slots()) {
            Instant at = nextCheck.get(slot);
            if ((at == null || !at.isAfter(now)) && !hostWaiting(slot, now)) due.addAll(pick(slot));
        }
        start(due, now);
    }

    /** The slot's shop refused a moment ago: none of its pages is asked before its wait is over. */
    private boolean hostWaiting(String slot, Instant now) {
        String url = StockRadarStores.WATCHES.stream().filter(watch -> watch.slot().equals(slot)).map(Watch::url).findFirst().orElse(null);
        Instant notBefore = url == null ? null : hostNotBefore.get(hostOf(url));
        return notBefore != null && notBefore.isAfter(now);
    }

    private void promote(String group, String key) {
        java.util.Deque<String> queue = promoted.computeIfAbsent(group, ignored -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (!queue.contains(key)) queue.addLast(key);
    }

    /** What "Nu opnieuw proberen" did: pages asked now, and when a page that had to wait its turn comes (seconds, 0 = none waits). */
    public record CheckStart(int started, long queuedSeconds) {}

    /**
     * "Nu opnieuw proberen": every shop, one shop or one watch. The one-minute
     * rule holds here too: a slot asked less than a minute ago is not asked
     * again. A watch of such a group goes next in its turns instead, so the
     * press still counts.
     */
    public CheckStart checkNow(String key) {
        if (!enabled()) return new CheckStart(0, 0);
        Instant now = Instant.now();
        List<Watch> chosen = new ArrayList<>();
        long queued = 0;
        for (String slot : slots()) {
            List<Watch> members = StockRadarStores.WATCHES.stream().filter(watch -> watch.slot().equals(slot)).toList();
            Watch exact = members.stream().filter(watch -> watch.key().equals(key)).findFirst().orElse(null);
            boolean picked = key == null || exact != null || members.stream().anyMatch(watch -> watch.shop().key().equals(key));
            if (!picked) continue;
            if (exact != null && exact.group() != null) promote(exact.group(), exact.key());
            Instant asked = slotAsked.get(slot);
            boolean waiting = hostWaiting(slot, now);
            if ((asked != null && asked.isAfter(now.minusSeconds(INTERVAL_SECONDS))) || waiting) {
                if ((exact != null && exact.group() != null) || waiting) {
                    Instant next = nextCheck.getOrDefault(slot, now);
                    Instant hostWait = members.isEmpty() ? null : hostNotBefore.get(hostOf(members.getFirst().url()));
                    if (hostWait != null && hostWait.isAfter(next)) next = hostWait;
                    queued = Math.max(queued, Math.max(0, java.time.Duration.between(now, next).toSeconds()));
                }
                continue;
            }
            chosen.addAll(pick(slot));
        }
        return new CheckStart(start(chosen, now), queued);
    }

    /** The slots in the order of the watches: Apple's two pages, the Coolblue groups, Vanden Borre's four pages. */
    static List<String> slots() {
        return StockRadarStores.WATCHES.stream().map(Watch::slot).distinct().toList();
    }

    /** What a due slot reads: every watch of the page, or the one whose turn it is in a group. */
    List<Watch> pick(String slot) {
        List<Watch> members = StockRadarStores.WATCHES.stream().filter(watch -> watch.slot().equals(slot)).toList();
        if (members.isEmpty() || members.getFirst().group() == null) return members;
        return List.of(turns(members).getFirst());
    }

    /** A group's turns: the promoted watches first, in order, then the one asked longest ago. */
    private List<Watch> turns(List<Watch> members) {
        List<String> queue = new ArrayList<>(promoted.getOrDefault(members.getFirst().group(), new java.util.ArrayDeque<>()));
        List<Watch> order = new ArrayList<>(members);
        order.sort(java.util.Comparator.<Watch>comparingInt(watch -> queue.contains(watch.key()) ? queue.indexOf(watch.key()) : Integer.MAX_VALUE)
                .thenComparing(watch -> lastAsked.getOrDefault(watch.key(), Instant.EPOCH)));
        return order;
    }

    private int start(List<Watch> due, Instant now) {
        if (due.isEmpty()) return 0;
        if (!roundBusy.compareAndSet(false, true)) return 0;
        List<Watch> round = List.copyOf(due);
        for (Watch watch : round) {
            lastAsked.put(watch.key(), now);
            slotAsked.put(watch.slot(), now);
            nextCheck.put(watch.slot(), jittered(now));
            if (watch.group() != null) {
                java.util.Deque<String> queue = promoted.get(watch.group());
                if (queue != null) queue.remove(watch.key());
            }
        }
        try {
            roundThread.submit(() -> {
                try { runRound(round); }
                catch (RuntimeException failure) { LOG.warnf("Voorraadradar-ronde mislukt (%s)", failure.getClass().getSimpleName()); }
                finally { roundBusy.set(false); }
            });
        } catch (RuntimeException rejected) {
            roundBusy.set(false);
            return 0;
        }
        return (int) round.stream().map(Watch::url).distinct().count();
    }

    // -------------------------------------------------------------------- round

    record Read(Watch watch, Object result) {}

    void runRound(List<Watch> round) {
        long started = System.nanoTime();
        greetOnce();
        Map<String, Object> pages = fetchAll(round.stream().map(Watch::url).distinct().toList());
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(BRUSSELS);
        List<Read> reads = new ArrayList<>();
        for (Watch watch : round) reads.add(new Read(watch, check(watch, pages.get(watch.url()), today)));
        // The wait after a refusal holds whatever the database does.
        pace(reads, now);
        followCoolblue(round, pages);
        List<Alert> alerts = QuarkusTransaction.requiringNew().call(() -> persist(reads, now, today));
        for (Alert alert : alerts) notify(alert, now);
        lastRoundAt = now;
        lastRoundMillis = (System.nanoTime() - started) / 1_000_000;
        long count = rounds.incrementAndGet();
        if (lastSummaryAt == null || lastSummaryAt.isBefore(now.minusSeconds(3600))) {
            lastSummaryAt = now;
            long open = reads.stream().filter(read -> read.result() instanceof Observation seen && availableNow(seen, today)).count();
            long failed = reads.stream().filter(read -> read.result() instanceof RuntimeException).count();
            LOG.infof("Voorraadradar: ronde %d, %d pagina's in %d ms, %d beschikbaar, %d niet leesbaar", count,
                    pages.size(), lastRoundMillis, open, failed);
        }
    }

    /** One virtual thread per shop; one shop's pages one after the other, with a short pause between them. */
    private Map<String, Object> fetchAll(List<String> urls) {
        Map<String, List<String>> byHost = new LinkedHashMap<>();
        for (String url : urls) byHost.computeIfAbsent(hostOf(url), host -> new ArrayList<>()).add(url);
        Map<String, Object> results = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (List<String> hostUrls : byHost.values()) {
            threads.add(Thread.ofVirtual().name("stock-radar-http").start(() -> {
                boolean first = true;
                for (String url : hostUrls) {
                    if (!first) {
                        try { Thread.sleep(SAME_HOST_PAUSE_MS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    first = false;
                    Object result;
                    try { result = http.fetch(url); }
                    catch (RuntimeException failure) { result = failure; }
                    results.put(url, result);
                    // A shop that just refused is not asked for its next page in this round.
                    if (result instanceof FetchFailure failure && failure.refused()) {
                        for (String rest : hostUrls) results.putIfAbsent(rest, failure);
                        return;
                    }
                }
            }));
        }
        // Every read is bounded (30 s to answer, 20 s to arrive); a whole round never waits longer than this.
        long deadline = System.nanoTime() + 150_000_000_000L;
        for (Thread thread : threads) {
            long left = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            try { thread.join(left); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        for (Thread thread : threads) if (thread.isAlive()) thread.interrupt();
        for (String url : urls) results.putIfAbsent(url, new FetchFailure("Winkel antwoordt niet op tijd"));
        return results;
    }

    /** The parser's verdict, or the failure that stands in for it. */
    private static Object check(Watch watch, Object fetched, LocalDate today) {
        try {
            if (fetched instanceof RuntimeException failure) throw failure;
            if (!(fetched instanceof Page page)) throw new FetchFailure("Geen antwoord");
            return watch.checker().check(page, today);
        } catch (FetchFailure failure) {
            return failure;
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new IllegalStateException(reason.startsWith("Pagina") ? reason : "Pagina niet herkend: " + reason);
        }
    }

    boolean availableNow(Observation seen, LocalDate today) {
        return seen.inStock() && !seen.dateUnknown()
                && (seen.deliveryBy() == null || !seen.deliveryBy().isAfter(today.plusDays(Math.max(0, maxDeliveryDays))));
    }

    // ------------------------------------------------------------------- state

    enum AlertKind { AVAILABLE, NEW_PLACE, GONE }

    record Alert(AlertKind kind, Watch watch, Observation seen, List<String> newPlaces) {}

    private List<Alert> persist(List<Read> reads, Instant now, LocalDate today) {
        Map<String, WatchStateEntity> rows = new HashMap<>();
        List<String> keys = reads.stream().map(read -> read.watch().key()).toList();
        for (WatchStateEntity row : WatchStateEntity.<WatchStateEntity>list("watchKey in ?1", keys)) rows.put(row.watchKey, row);
        List<Alert> alerts = new ArrayList<>();
        for (Read read : reads) {
            Watch watch = read.watch();
            WatchStateEntity row = rows.get(watch.key());
            if (row == null) {
                row = new WatchStateEntity();
                row.watchKey = watch.key();
                row.checkCount = 0L;
                row.errorCount = 0;
                row.goneReads = 0;
            }
            row.checkedAt = now;
            row.checkCount = (row.checkCount == null ? 0 : row.checkCount) + 1;
            if (read.result() instanceof Observation seen) {
                Alert alert = observe(row, watch, seen, now, today);
                if (alert != null) alerts.add(alert);
            } else {
                failed(row, (RuntimeException) read.result());
            }
            row.persist();
        }
        return alerts;
    }

    private Alert observe(WatchStateEntity row, Watch watch, Observation seen, Instant now, LocalDate today) {
        boolean available = availableNow(seen, today);
        Boolean before = row.available;
        Set<String> announced = places(row.announcedPlaces);
        Alert alert = null;
        if (available) {
            row.goneReads = 0;
            if (!Boolean.TRUE.equals(before)) {
                row.available = true;
                row.changedAt = now;
                alert = new Alert(AlertKind.AVAILABLE, watch, seen, List.of());
            } else {
                // Stores not announced yet in this available period, once its "beschikbaar" got out. Announced stores are
                // stored on delivery only, so a store alert that did not get out comes again (spaced like any retry).
                boolean told = row.notifiedAt != null && row.changedAt != null && !row.notifiedAt.isBefore(row.changedAt);
                List<String> joined = seen.places().stream().filter(place -> !announced.contains(place)).toList();
                Instant notBefore = retryNotBefore.get(watch.key());
                if (told && !joined.isEmpty() && (notBefore == null || !notBefore.isAfter(now))) alert = new Alert(AlertKind.NEW_PLACE, watch, seen, joined);
            }
        } else if (Boolean.TRUE.equals(before)) {
            int gone = (row.goneReads == null ? 0 : row.goneReads) + 1;
            if (gone >= GONE_READS) {
                row.available = false;
                row.changedAt = now;
                row.goneReads = 0;
                row.announcedPlaces = null;
                retryNotBefore.remove(watch.key());
                retryCount.remove(watch.key());
                alert = new Alert(AlertKind.GONE, watch, seen, List.of());
            } else {
                row.goneReads = gone;
            }
        } else {
            row.available = false;
            row.goneReads = 0;
        }
        /*
         * An "available" whose alert never got out (every door refused, a restart in between) is sent again, only
         * on a read that is available itself, and each time a little later (1, 2, 4 ... at most 60 min).
         */
        if (alert == null && available && Boolean.TRUE.equals(row.available) && row.changedAt != null
                && (row.notifiedAt == null || row.notifiedAt.isBefore(row.changedAt))) {
            Instant notBefore = retryNotBefore.get(watch.key());
            if (notBefore == null || !notBefore.isAfter(now)) alert = new Alert(AlertKind.AVAILABLE, watch, seen, List.of());
        }
        row.readAvailable = available;
        row.inStock = seen.inStock();
        row.statusText = seen.statusText();
        row.deliveryBy = seen.deliveryBy();
        row.priceEur = seen.priceEur();
        row.places = joinPlaces(seen.places());
        row.goodAt = now;
        row.lastError = null;
        row.errorCount = 0;
        row.refused = false;
        return alert;
    }

    private void failed(WatchStateEntity row, RuntimeException failure) {
        String message = failure.getMessage() == null ? "Controle mislukt" : failure.getMessage();
        row.lastError = message.length() > 480 ? message.substring(0, 480) : message;
        row.errorCount = (row.errorCount == null ? 0 : row.errorCount) + 1;
        row.refused = failure instanceof FetchFailure fetch && fetch.refused();
    }

    /**
     * The next time per slot after a round: the normal minute, or a wait that
     * grows while the slot keeps failing. Asked again later, not harder.
     */
    private void pace(List<Read> reads, Instant now) {
        Map<String, List<Read>> bySlot = new LinkedHashMap<>();
        for (Read read : reads) bySlot.computeIfAbsent(read.watch().slot(), slot -> new ArrayList<>()).add(read);
        for (Map.Entry<String, List<Read>> entry : bySlot.entrySet()) {
            String slot = entry.getKey();
            RuntimeException failure = entry.getValue().stream().map(Read::result)
                    .filter(RuntimeException.class::isInstance).map(RuntimeException.class::cast).findFirst().orElse(null);
            boolean allFailed = entry.getValue().stream().allMatch(read -> read.result() instanceof RuntimeException);
            if (!allFailed) { slotErrors.remove(slot); continue; }
            int errors = slotErrors.merge(slot, 1, Integer::sum);
            boolean refused = failure instanceof FetchFailure fetch && fetch.refused();
            long wait = Math.min(refused ? MAX_REFUSED_BACKOFF_SECONDS : MAX_BACKOFF_SECONDS, (long) interval() * (1L << Math.min(4, errors)));
            nextCheck.put(slot, now.plusSeconds(wait));
            if (refused) hostNotBefore.merge(hostOf(entry.getValue().getFirst().watch().url()), now.plusSeconds(wait), (a, b) -> a.isAfter(b) ? a : b);
            if (errors <= 3 || errors % 60 == 0) {
                LOG.infof("Voorraadradar %s: %s (%d na elkaar, opnieuw over %d s)", entry.getValue().getFirst().watch().key(),
                        failure == null ? "Controle mislukt" : failure.getMessage(), errors, wait);
            }
        }
    }

    /**
     * Every Coolblue page names the online state of all four items. A item
     * that turns InStock there goes next in its group, at once: one extra page,
     * only on the change.
     */
    private void followCoolblue(List<Watch> round, Map<String, Object> pages) {
        for (Watch read : round) {
            if (read.group() == null || !(pages.get(read.url()) instanceof Page page)) continue;
            Map<String, String> states = StockRadarStores.coolblueVariants(page.html());
            for (Watch sibling : StockRadarStores.WATCHES) {
                if (!read.group().equals(sibling.group())) continue;
                String state = states.get(sibling.item().key());
                if (state == null) continue;
                String before = variantState.put(sibling.key(), state);
                if (sibling.key().equals(read.key())) continue;
                if (inStock(state) && !inStock(before)) {
                    java.util.Deque<String> queue = promoted.get(read.group());
                    boolean first = queue == null || queue.isEmpty();
                    promote(read.group(), sibling.key());
                    // The first one at once; more of them follow at the shop's own pace, ahead of the usual turns.
                    if (first) nextCheck.put(read.group(), Instant.now());
                    LOG.infof("Voorraadradar %s: Coolblue meldt %s, de pagina volgt %s", sibling.key(), state, first ? "meteen" : "als volgende");
                }
            }
        }
    }

    private static boolean inStock(String state) {
        return "InStock".equals(state) || "OnStock".equals(state);
    }

    /** At most 2000 characters, whole names only. */
    static String joinPlaces(List<String> places) {
        if (places == null || places.isEmpty()) return null;
        StringBuilder joined = new StringBuilder();
        for (String place : places) {
            String next = (joined.isEmpty() ? "" : ", ") + place;
            if (joined.length() + next.length() > 2000) break;
            joined.append(next);
        }
        return joined.toString();
    }

    private static Set<String> places(String stored) {
        if (stored == null || stored.isBlank()) return Set.of();
        Set<String> set = new LinkedHashSet<>();
        for (String place : Arrays.asList(stored.split(","))) if (!place.isBlank()) set.add(place.trim());
        return set;
    }

    // ----------------------------------------------------------- notifications

    private void notify(Alert alert, Instant now) {
        Watch watch = alert.watch();
        Observation seen = alert.seen();
        String item = watch.item().title();
        String icon = watch.item().family().icon();
        String price = seen.priceEur() == null ? "" : " · € " + formatEuro(seen.priceEur());
        String tag = "stock-radar:" + watch.key();
        String title;
        String body;
        String url;
        boolean sendMail;
        switch (alert.kind()) {
            case AVAILABLE -> {
                title = icon + " " + item + " beschikbaar bij " + watch.shop().name();
                body = seen.statusText() + price + "\n" + hostOf(watch.orderUrl());
                url = watch.orderUrl();
                sendMail = true;
            }
            case NEW_PLACE -> {
                title = icon + " " + item + " nu ook in " + String.join(", ", alert.newPlaces());
                body = seen.statusText() + price + "\n" + hostOf(watch.orderUrl());
                url = watch.orderUrl();
                sendMail = true;
            }
            default -> {
                title = item + " weer weg bij " + watch.shop().name();
                body = seen.statusText();
                url = "/voorraadradar";
                sendMail = false;
            }
        }
        List<Integer> statuses = List.of();
        if (pushEnabled) {
            try { statuses = push.send(notifyUser, title, body, url, tag, alert.kind() == AlertKind.GONE ? 3600 : 6 * 3600); }
            catch (RuntimeException failure) { LOG.warnf("Voorraadradar-push mislukt (%s)", failure.getClass().getSimpleName()); statuses = List.of(-1); }
        }
        Integer mailStatus = null;
        boolean mailArrived = false;
        if (mailEnabled && sendMail) {
            String text = String.join("\n",
                    watch.item().product(),
                    "",
                    (alert.kind() == AlertKind.NEW_PLACE ? "Nu ook op voorraad in " + String.join(", ", alert.newPlaces()) + " bij " : "BESCHIKBAAR bij ")
                            + watch.shop().name() + " (" + watch.shop().kind() + ")",
                    "Status: " + seen.statusText(),
                    seen.priceEur() == null ? "" : "Prijs: € " + formatEuro(seen.priceEur()),
                    seen.deliveryBy() == null ? "" : "Levering/afhaling: " + seen.deliveryBy(),
                    "Bestellen: " + watch.orderUrl(),
                    "",
                    "Gezien op " + ZonedDateTime.ofInstant(now, BRUSSELS).format(STAMP) + " door de Enrosed voorraadradar.");
            MailRound round = mailAll(title.substring(icon.length()).trim(), text);
            mailStatus = round.mainStatus();
            // Any address that got it counts: a retry would mail everyone again.
            mailArrived = round.sent() > 0;
        }
        Integer pushStatus = statuses.isEmpty() ? null : statuses.stream().filter(s -> s >= 200 && s < 300).findFirst().orElse(statuses.getFirst());
        Integer mailResult = mailStatus;
        // No door at all (push off or no device, and no mail for this alert): nothing to retry.
        boolean noDoor = statuses.isEmpty() && mailStatus == null;
        boolean delivered = alert.kind() == AlertKind.GONE || noDoor || mailArrived
                || (pushStatus != null && pushStatus >= 200 && pushStatus < 300);
        if (alert.kind() != AlertKind.GONE) {
            if (delivered) {
                retryNotBefore.remove(watch.key());
                retryCount.remove(watch.key());
            } else {
                int tries = retryCount.merge(watch.key(), 1, Integer::sum);
                retryNotBefore.put(watch.key(), now.plusSeconds(60L * Math.min(60, 1L << Math.min(6, tries - 1))));
            }
        }
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                WatchStateEntity row = WatchStateEntity.byKey(watch.key());
                if (row == null || alert.kind() == AlertKind.GONE) return;
                // The statuses belong to this alert only: a door that was not used shows nothing.
                row.lastPushStatus = pushStatus;
                row.lastMailStatus = mailResult;
                if (delivered) {
                    row.notifiedAt = now;
                    Set<String> told = new LinkedHashSet<>(places(row.announcedPlaces));
                    told.addAll(seen.places());
                    row.announcedPlaces = joinPlaces(new ArrayList<>(told));
                }
                row.persist();
            });
        } catch (RuntimeException failure) {
            LOG.warnf("Voorraadradar: meldingsstatus niet opgeslagen voor %s", watch.key());
        }
        LOG.infof("Voorraadradar: %s - %s (push %s, mail %s)", title, seen.statusText(), statuses, mailStatus);
    }

    /**
     * The very first start (no page was ever checked): one push and one mail
     * that say the radar runs, and how push reaches a phone when none is set up yet.
     */
    private void greetOnce() {
        if (!greeted.compareAndSet(false, true)) return;
        try {
            if (QuarkusTransaction.requiringNew().call(() -> WatchStateEntity.count()) > 0) return;
            List<Integer> statuses = pushEnabled ? sendTestPush("🔔 Voorraadradar staat weer aan").pushStatuses() : List.of();
            String devices = statuses.isEmpty()
                    ? "Push: er is nog geen toestel aangemeld. Open de ERP-webapp op je gsm (vanaf het beginscherm) en zet Meer > Meldingen aan."
                    : "Push: verstuurd naar " + statuses.size() + " toestel(len).";
            int mailStatus = mailEnabled ? mailAll("Voorraadradar staat weer aan", String.join("\n",
                    "De Enrosed voorraadradar draait weer, zonder browser.",
                    watched(),
                    "Zodra een winkel het binnen " + maxDeliveryDays + " dagen kan leveren of laten afhalen, of een Coolblue-winkel het op voorraad heeft, krijg je een mail en een pushmelding.",
                    devices)).mainStatus() : -2;
            LOG.infof("Voorraadradar: startbericht verstuurd (push %s, mail %d)", statuses, mailStatus);
        } catch (RuntimeException failure) {
            LOG.warnf("Voorraadradar: startbericht mislukt (%s)", failure.getClass().getSimpleName());
        }
    }

    /** The test buttons: the same doors as a real alert, with a recognisable message. */
    public PushTest sendTestPush() { return sendTestPush("🔔 Voorraadradar werkt"); }

    private PushTest sendTestPush(String title) {
        if (!pushEnabled || !push.enabled()) return new PushTest(0, List.of(), false);
        List<Integer> statuses = push.send(notifyUser, title,
                "Zo komt een melding van de voorraadradar binnen op dit toestel.", "/voorraadradar", "stock-radar:test", 600);
        return new PushTest(statuses.size(), statuses, true);
    }

    public MailTest sendTestMail() {
        if (!mailEnabled) return new MailTest(-2, mailTo, "De radar mailt niet (STOCK_RADAR_MAIL_ENABLED)", 0, 0, List.of(), false);
        MailRound round = mailAll("Voorraadradar: testmelding", String.join("\n",
                "Dit is een testmelding van de Enrosed voorraadradar.",
                watched(),
                "Zodra een winkel het binnen " + maxDeliveryDays + " dagen kan leveren of laten afhalen, krijg je zo'n mail en een pushmelding.",
                "", "Verstuurd op " + ZonedDateTime.now(BRUSSELS).format(STAMP) + "."));
        return new MailTest(round.mainStatus() == null ? -1 : round.mainStatus(), mailTo, round.mainDetail(), round.sent(), round.total(), round.failed(), mail.mock());
    }

    /** What one mail to every address did: the configured address's answer, and how many of all got it. */
    record MailRound(Integer mainStatus, String mainDetail, int sent, int total, List<String> failed) {}

    /**
     * One mail per address (the configured one first, then the extra ones), so
     * nobody sees the other addresses. A failing extra address never stops the others.
     */
    MailRound mailAll(String subject, String text) {
        List<String> to = new ArrayList<>();
        to.add(mailTo);
        try { for (String extra : extraRecipients()) if (!extra.equalsIgnoreCase(mailTo)) to.add(extra); }
        catch (RuntimeException failure) { LOG.warnf("Voorraadradar: extra mailadressen niet gelezen (%s)", failure.getClass().getSimpleName()); }
        Integer main = null;
        String mainDetail = null;
        int sent = 0;
        List<String> failed = new ArrayList<>();
        for (String address : to) {
            StockRadarMailSender.Result result = mail.sendWithDetail(address, subject, text);
            if (main == null) { main = result.status(); mainDetail = result.detail(); }
            if (result.status() == 0 || (result.status() >= 200 && result.status() < 300)) sent++;
            else failed.add(address);
        }
        return new MailRound(main, mainDetail, sent, to.size(), failed);
    }

    /** The extra addresses, oldest first. */
    public List<String> extraRecipients() {
        return QuarkusTransaction.requiringNew().call(() -> RecipientEntity.<RecipientEntity>listAll(io.quarkus.panache.common.Sort.by("createdAt").and("id"))
                .stream().map(row -> row.email).toList());
    }

    /**
     * Replaces the extra addresses with this list: trimmed, lower case, valid,
     * without doubles or the configured address, at most five. An address that
     * stays keeps its place.
     */
    public List<String> setExtraRecipients(List<String> emails) {
        List<String> clean = new ArrayList<>();
        for (String raw : emails == null ? List.<String>of() : emails) {
            String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (email.isEmpty()) continue;
            if (email.length() > 254 || !EMAIL.matcher(email).matches()) throw new UnprocessableBusinessRuleException("Geen geldig mailadres: " + raw.trim());
            if (email.equalsIgnoreCase(mailTo) || clean.contains(email)) continue;
            clean.add(email);
        }
        if (clean.size() > MAX_EXTRA_RECIPIENTS) throw new UnprocessableBusinessRuleException("Hoogstens " + MAX_EXTRA_RECIPIENTS + " extra mailadressen");
        QuarkusTransaction.requiringNew().run(() -> {
            List<RecipientEntity> rows = RecipientEntity.listAll();
            for (RecipientEntity row : rows) if (!clean.contains(row.email)) row.delete();
            Set<String> kept = new java.util.HashSet<>();
            for (RecipientEntity row : rows) if (clean.contains(row.email)) kept.add(row.email);
            Instant now = Instant.now();
            for (String email : clean) {
                if (kept.contains(email)) continue;
                RecipientEntity row = new RecipientEntity();
                row.email = email;
                row.createdAt = now;
                row.persist();
                now = now.plusMillis(1);
            }
        });
        LOG.infof("Voorraadradar: %d extra mailadres(sen)", clean.size());
        return clean;
    }

    /** One line per product: what is watched and where, every minute. */
    static String watched() {
        List<String> lines = new ArrayList<>();
        for (Family family : StockRadarStores.FAMILIES) {
            lines.add("Bewaakt: " + family.label() + " (" + family.subtitle() + ") bij "
                    + String.join(", ", StockRadarStores.shopsOf(family).stream().map(Shop::name).toList()) + ", elke minuut.");
        }
        return String.join("\n", lines);
    }

    public record PushTest(int devices, List<Integer> pushStatuses, boolean pushOn) {}

    /** {@code sent} of {@code total} addresses got the test mail; {@code mock}: the mailer only logs (local, test). */
    public record MailTest(int mailStatus, String mailTo, String detail, int sent, int total, List<String> failed, boolean mock) {}

    // -------------------------------------------------------------------- feed

    public record ItemRow(String key, String label, String colour, String product, String applePart, BigDecimal listPrice) {}

    public record ShopRow(String key, String name, String shortName, String kind) {}

    /** One table on the screen: a product, its variants as columns, its shops as rows, and the shops that refuse. */
    public record FamilyRow(String key, String label, String subtitle, List<ItemRow> items, List<ShopRow> shops, List<ManualShop> manual) {}

    /**
     * {@code rhythmSeconds}: how often this page is read, a minute, or a minute per item of a group that takes turns.
     * {@code refused}: the last failure was the shop saying no (403, 429, 503, 541, a robot check), not a broken page.
     */
    public record WatchRow(String key, String family, String shop, String item, String url, String orderUrl, int rhythmSeconds,
                           Boolean available, Boolean readAvailable, Boolean inStock, String statusText, LocalDate deliveryBy, BigDecimal priceEur, String places,
                           Instant checkedAt, Instant goodAt, Instant changedAt, Instant notifiedAt, Instant nextCheckAt, String lastError,
                           int errorCount, boolean refused, long checkCount, Integer lastPushStatus, Integer lastMailStatus) {}

    public record Feed(boolean enabled, boolean pushEnabled, boolean mailEnabled, String notifyUser, String mailTo, List<String> extraMailTo,
                       int maxDeliveryDays, int intervalSeconds, int devices, Instant lastTickAt, Instant lastRoundAt,
                       long lastRoundMillis, Instant now, List<FamilyRow> families, List<WatchRow> watches) {}

    public Feed feed() {
        Map<String, WatchStateEntity> rows = new HashMap<>();
        for (WatchStateEntity row : WatchStateEntity.<WatchStateEntity>listAll()) rows.put(row.watchKey, row);
        List<WatchRow> list = new ArrayList<>();
        for (Watch watch : StockRadarStores.WATCHES) {
            WatchStateEntity row = rows.get(watch.key());
            int turns = watch.group() == null ? 1 : (int) StockRadarStores.WATCHES.stream().filter(other -> watch.group().equals(other.group())).count();
            list.add(new WatchRow(watch.key(), watch.item().family().key(), watch.shop().key(), watch.item().key(), watch.url(), watch.orderUrl(), interval() * turns,
                    row == null ? null : row.available, row == null ? null : row.readAvailable,
                    row == null ? null : row.inStock, row == null ? null : row.statusText,
                    row == null ? null : row.deliveryBy, row == null ? null : row.priceEur, row == null ? null : row.places,
                    row == null ? null : row.checkedAt, row == null ? null : row.goodAt, row == null ? null : row.changedAt,
                    row == null ? null : row.notifiedAt, nextFor(watch), row == null ? null : row.lastError,
                    row == null || row.errorCount == null ? 0 : row.errorCount, row != null && Boolean.TRUE.equals(row.refused),
                    row == null || row.checkCount == null ? 0 : row.checkCount,
                    row == null ? null : row.lastPushStatus, row == null ? null : row.lastMailStatus));
        }
        int devices;
        try { devices = push.deviceCount(notifyUser); } catch (RuntimeException failure) { devices = 0; }
        List<FamilyRow> families = new ArrayList<>();
        for (Family family : StockRadarStores.FAMILIES) {
            List<ItemRow> items = StockRadarStores.itemsOf(family).stream()
                    .map(item -> new ItemRow(item.key(), item.label(), item.colour(), item.product(), item.applePart(), item.listPrice())).toList();
            List<ShopRow> shops = StockRadarStores.shopsOf(family).stream().map(shop -> new ShopRow(shop.key(), shop.name(), shop.shortName(), shop.kind())).toList();
            List<ManualShop> manual = StockRadarStores.MANUAL.stream().filter(shop -> shop.family().equals(family.key())).toList();
            families.add(new FamilyRow(family.key(), family.label(), family.subtitle(), items, shops, manual));
        }
        // Unknown (null) when the list cannot be read: never an empty list the screen would then save over the real one.
        List<String> extra;
        try { extra = extraRecipients().stream().filter(address -> !address.equalsIgnoreCase(mailTo)).toList(); }
        catch (RuntimeException failure) { extra = null; }
        return new Feed(enabled(), pushEnabled && push.enabled(), mailEnabled, notifyUser, mailTo, extra, maxDeliveryDays, interval(),
                devices, lastTickAt, lastRoundAt, lastRoundMillis, Instant.now(), families, list);
    }

    /** When this watch's page is read next: its slot's time, plus a minute per turn ahead of it in a group. */
    private Instant nextFor(Watch watch) {
        Instant slotNext = nextCheck.get(watch.slot());
        Instant hostWait = hostNotBefore.get(hostOf(watch.url()));
        if (slotNext != null && hostWait != null && hostWait.isAfter(slotNext)) slotNext = hostWait;
        if (slotNext == null || watch.group() == null) return slotNext;
        List<Watch> members = StockRadarStores.WATCHES.stream().filter(other -> watch.group().equals(other.group())).toList();
        int turn = turns(members).indexOf(watch);
        return slotNext.plusSeconds((long) Math.max(0, turn) * interval());
    }

    static String formatEuro(BigDecimal amount) {
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(Locale.of("nl", "BE"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }

    static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? url : uri.getHost().replaceFirst("^www\\.", "");
        } catch (RuntimeException e) {
            return url;
        }
    }
}
