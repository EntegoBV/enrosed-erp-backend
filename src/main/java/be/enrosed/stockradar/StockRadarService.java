package be.enrosed.stockradar;

import be.enrosed.stockradar.StockRadar.FetchFailure;
import be.enrosed.stockradar.StockRadar.Method;
import be.enrosed.stockradar.StockRadar.Observation;
import be.enrosed.stockradar.StockRadar.Page;
import be.enrosed.stockradar.StockRadar.Store;
import be.enrosed.stockradar.StockRadarEntities.StoreStateEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Timing, state and notifications of the stock radar.
 *
 * Every tick runs the shops that are due. Plain HTTP shops run in parallel on
 * virtual threads; browser shops go to one background thread so a slow
 * Chromium never delays the 20-second Apple check. A shop's "available"
 * verdict is stored per shop; only a flip of that verdict sends a push and a
 * mail, so a shop that stays sold out is never reported twice.
 */
@ApplicationScoped
public class StockRadarService {

    private static final Logger LOG = Logger.getLogger(StockRadarService.class);
    static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.of("nl", "BE"));

    @ConfigProperty(name = "enrosed.stockradar.enabled", defaultValue = "true") boolean enabled;
    @ConfigProperty(name = "enrosed.stockradar.push-enabled", defaultValue = "true") boolean pushEnabled;
    @ConfigProperty(name = "enrosed.stockradar.mail-enabled", defaultValue = "true") boolean mailEnabled;
    @ConfigProperty(name = "enrosed.stockradar.notify-user", defaultValue = "emre") String notifyUser;
    @ConfigProperty(name = "enrosed.stockradar.mail-to", defaultValue = "emre@entego.be") String mailTo;
    @ConfigProperty(name = "enrosed.stockradar.max-delivery-days", defaultValue = "3") int maxDeliveryDays;
    @ConfigProperty(name = "enrosed.stockradar.browser-enabled", defaultValue = "true") boolean browserEnabled;
    @ConfigProperty(name = "enrosed.stockradar.browser-timeout-seconds", defaultValue = "40") int browserTimeoutSeconds;
    /** Multiplies every shop's interval: 2.0 halves the traffic, 0.5 doubles it. */
    @ConfigProperty(name = "enrosed.stockradar.speed", defaultValue = "1.0") double speed;

    private final StockRadarPushSender push;
    private final StockRadarMailSender mail;
    private final StockRadarHttpFetcher http = new StockRadarHttpFetcher();
    private final StockRadarBrowserFetcher browser = new StockRadarBrowserFetcher();
    private final Map<String, Instant> nextCheck = new ConcurrentHashMap<>();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final AtomicBoolean browserBusy = new AtomicBoolean();
    private final ExecutorService browserThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stock-radar-browser");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Instant lastTickAt;

    public StockRadarService(StockRadarPushSender push, StockRadarMailSender mail) {
        this.push = push;
        this.mail = mail;
    }

    @PostConstruct
    void stagger() {
        Instant now = Instant.now();
        for (Store store : StockRadarStores.STORES) {
            nextCheck.put(store.key(), now.plusSeconds(ThreadLocalRandom.current().nextInt(5, Math.max(6, Math.min(interval(store), 40)))));
        }
        LOG.infof("Voorraadradar %s: %d winkels, meldingen naar %s (push %s, mail %s naar %s), levering binnen %d dagen",
                enabled ? "actief" : "uit", StockRadarStores.STORES.size(), notifyUser, pushEnabled, mailEnabled, mailTo, maxDeliveryDays);
    }

    public String notifyUser() { return notifyUser; }

    @PreDestroy
    void stop() { browserThread.shutdownNow(); }

    public boolean enabled() { return enabled; }

    int interval(Store store) { return (int) Math.max(5, Math.round(store.intervalSeconds() * (speed <= 0 ? 1.0 : speed))); }

    private Instant jittered(Store store, Instant from) {
        double factor = 0.85 + ThreadLocalRandom.current().nextDouble() * 0.3;
        return from.plusMillis(Math.round(interval(store) * 1000L * factor));
    }

    // -------------------------------------------------------------------- ticks

    public void tick(Instant now) {
        if (!enabled) return;
        if (!ticking.compareAndSet(false, true)) return;
        try {
            lastTickAt = now;
            List<Store> due = new ArrayList<>();
            for (Store store : StockRadarStores.STORES) {
                Instant at = nextCheck.get(store.key());
                if (at == null || !at.isAfter(now)) due.add(store);
            }
            if (!due.isEmpty()) run(due, now);
        } finally {
            ticking.set(false);
        }
    }

    /** Runs the given shops now; browser shops are handed to the background thread. */
    void run(List<Store> stores, Instant now) {
        List<Store> plain = new ArrayList<>();
        List<Store> rendered = new ArrayList<>();
        for (Store store : stores) {
            nextCheck.put(store.key(), jittered(store, now));
            (store.method() == Method.HTTP ? plain : rendered).add(store);
        }
        if (!plain.isEmpty()) runHttp(plain);
        if (rendered.isEmpty()) return;
        if (!browserEnabled) {
            for (Store store : rendered) recordError(store, "Browsercontrole staat uit (STOCK_RADAR_BROWSER_ENABLED)", Instant.now());
            return;
        }
        if (!browserBusy.compareAndSet(false, true)) {
            for (Store store : rendered) nextCheck.put(store.key(), Instant.now().plusSeconds(15));
            return;
        }
        browserThread.submit(() -> {
            try { runBrowser(rendered); }
            catch (RuntimeException failure) { LOG.warnf("Voorraadradar-browserronde mislukt (%s)", failure.getClass().getSimpleName()); }
            finally { browserBusy.set(false); }
        });
    }

    private void runHttp(List<Store> stores) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<Store, Future<Object>> pending = new java.util.LinkedHashMap<>();
            for (Store store : stores) pending.put(store, executor.submit(() -> {
                try { return http.fetch(store.url()); } catch (RuntimeException failure) { return failure; }
            }));
            for (Map.Entry<Store, Future<Object>> entry : pending.entrySet()) {
                Object result;
                try { result = entry.getValue().get(35, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException timeout) { entry.getValue().cancel(true); result = new FetchFailure("Winkel antwoordt niet op tijd"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                catch (java.util.concurrent.ExecutionException failure) { result = new FetchFailure("Controle mislukt"); }
                record(entry.getKey(), result, Instant.now());
            }
        }
    }

    private void runBrowser(List<Store> stores) {
        for (Method method : List.of(Method.BROWSER, Method.CHROME)) {
            List<Store> batch = stores.stream().filter(store -> store.method() == method).toList();
            if (batch.isEmpty()) continue;
            Map<String, Object> results = browser.fetchAll(batch.stream().map(Store::url).toList(), browserTimeoutSeconds, method == Method.CHROME);
            for (Store store : batch) record(store, results.get(store.url()), Instant.now());
        }
    }

    // ------------------------------------------------------------------- state

    record Transition(boolean flipped, Boolean before, boolean after, long checks) {}

    void record(Store store, Object fetched, Instant now) {
        LocalDate today = LocalDate.now(BRUSSELS);
        try {
            if (fetched instanceof RuntimeException failure) throw failure;
            Page page = fetched instanceof Page p ? p : null;
            if (page == null) throw new FetchFailure("Geen antwoord");
            Observation seen = store.checker().check(page, today);
            boolean available = seen.inStock() && (seen.deliveryBy() == null || !seen.deliveryBy().isAfter(today.plusDays(Math.max(0, maxDeliveryDays))));
            Transition transition = QuarkusTransaction.requiringNew().call(() -> persist(store, seen, available, now));
            if (transition.flipped()) notify(store, seen, available, now);
            else if (transition.checks() % 50 == 1) LOG.infof("Voorraadradar %s: %s", store.key(), seen.statusText());
        } catch (FetchFailure failure) {
            recordError(store, failure.getMessage(), now);
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            recordError(store, reason.startsWith("Pagina") ? reason : "Pagina niet herkend: " + reason, now);
        }
    }

    private Transition persist(Store store, Observation seen, boolean available, Instant now) {
        StoreStateEntity row = StoreStateEntity.byKey(store.key());
        if (row == null) { row = new StoreStateEntity(); row.storeKey = store.key(); row.checkCount = 0L; row.errorCount = 0; }
        Boolean before = row.available;
        boolean flipped = before == null ? available : before != available;
        row.available = available;
        row.inStock = seen.inStock();
        row.statusText = seen.statusText();
        row.deliveryBy = seen.deliveryBy();
        row.priceEur = seen.priceEur();
        row.foundUrl = seen.foundUrl();
        row.checkedAt = now;
        row.lastError = null;
        row.errorCount = 0;
        row.checkCount = (row.checkCount == null ? 0 : row.checkCount) + 1;
        if (flipped) row.changedAt = now;
        row.persist();
        return new Transition(flipped, before, available, row.checkCount);
    }

    private void recordError(Store store, String message, Instant now) {
        String text = message == null ? "Controle mislukt" : message.length() > 480 ? message.substring(0, 480) : message;
        int errors = 0;
        try {
            errors = QuarkusTransaction.requiringNew().call(() -> {
                StoreStateEntity row = StoreStateEntity.byKey(store.key());
                if (row == null) { row = new StoreStateEntity(); row.storeKey = store.key(); row.checkCount = 0L; row.errorCount = 0; }
                row.checkedAt = now;
                row.lastError = text;
                row.errorCount = (row.errorCount == null ? 0 : row.errorCount) + 1;
                row.checkCount = (row.checkCount == null ? 0 : row.checkCount) + 1;
                row.persist();
                return row.errorCount;
            });
        } catch (RuntimeException failure) {
            LOG.warnf("Voorraadradar-fout niet opgeslagen voor %s", store.key());
        }
        if (errors <= 3 || errors % 50 == 0) LOG.infof("Voorraadradar %s: %s (%d na elkaar)", store.key(), text, errors);
    }

    // ----------------------------------------------------------- notifications

    private void notify(Store store, Observation seen, boolean available, Instant now) {
        String price = seen.priceEur() == null ? "" : " · € " + formatEuro(seen.priceEur());
        String link = seen.foundUrl() != null ? seen.foundUrl() : store.orderUrl();
        String title = available
                ? "📱 iPhone Duo beschikbaar bij " + store.name()
                : "iPhone Duo weer weg bij " + store.name();
        String body = available
                ? seen.statusText() + price + " · " + store.kindLabel()
                : seen.statusText();
        String url = available ? link : "/voorraadradar";
        List<Integer> statuses = List.of();
        Integer mailStatus = null;
        if (pushEnabled) {
            try { statuses = push.send(notifyUser, "stock-radar", title, body + "\n" + hostOf(link), url, "stock-radar:" + store.key(), 6 * 3600); }
            catch (RuntimeException failure) { LOG.warnf("Voorraadradar-push mislukt (%s)", failure.getClass().getSimpleName()); statuses = List.of(-1); }
        }
        if (mailEnabled) {
            String text = String.join("\n",
                    StockRadarStores.PRODUCT,
                    "",
                    (available ? "BESCHIKBAAR bij " : "Niet meer beschikbaar bij ") + store.name() + " (" + store.kindLabel() + ")",
                    "Status: " + seen.statusText(),
                    seen.priceEur() == null ? "" : "Prijs: € " + formatEuro(seen.priceEur()),
                    seen.deliveryBy() == null ? "" : "Levering/afhaling: " + seen.deliveryBy(),
                    "Bestellen: " + link,
                    "",
                    "Gezien op " + ZonedDateTime.ofInstant(now, BRUSSELS).format(STAMP) + " door de Enrosed voorraadradar (test).");
            mailStatus = mail.send(mailTo, title.replace("📱 ", ""), text);
        }
        Integer pushStatus = statuses.isEmpty() ? null : statuses.stream().filter(s -> s >= 200 && s < 300).findFirst().orElse(statuses.getFirst());
        Integer mailResult = mailStatus;
        QuarkusTransaction.requiringNew().run(() -> {
            StoreStateEntity row = StoreStateEntity.byKey(store.key());
            if (row == null) return;
            row.notifiedAt = now;
            row.lastPushStatus = pushStatus;
            row.lastMailStatus = mailResult;
            row.persist();
        });
        LOG.infof("Voorraadradar: %s - %s (push %s, mail %s)", title, seen.statusText(), statuses, mailStatus);
    }

    /** The manual test button: the same two doors, with a recognisable message. */
    public TestResult sendTest() {
        String title = "🔔 Voorraadradar werkt";
        String body = "Zo komt een melding over de iPhone Duo binnen op dit toestel.";
        List<Integer> statuses = push.send(notifyUser, "stock-radar", title, body, "/voorraadradar", "stock-radar:test", 600);
        int mailStatus = mail.send(mailTo, "Voorraadradar: testmelding", String.join("\n",
                "Dit is een testmelding van de Enrosed voorraadradar (testomgeving).",
                "Bewaakt: " + StockRadarStores.PRODUCT,
                "Zodra een winkel het toestel binnen " + maxDeliveryDays + " dagen kan leveren of laten afhalen, krijg je zo'n mail en een pushmelding.",
                "", "Verstuurd op " + ZonedDateTime.now(BRUSSELS).format(STAMP) + "."));
        return new TestResult(statuses.size(), statuses, mailStatus, mailTo);
    }

    public record TestResult(int devices, List<Integer> pushStatuses, int mailStatus, String mailTo) {}

    // -------------------------------------------------------------------- feed

    public record Row(String key, String name, String kindLabel, String method, String url, String orderUrl, int intervalSeconds,
                      Boolean available, Boolean inStock, String statusText, LocalDate deliveryBy, BigDecimal priceEur, String foundUrl,
                      Instant checkedAt, Instant changedAt, Instant notifiedAt, Instant nextCheckAt, String lastError,
                      int errorCount, long checkCount, Integer lastPushStatus, Integer lastMailStatus) {}

    public record Feed(boolean enabled, boolean pushEnabled, boolean mailEnabled, boolean browserEnabled, String notifyUser, String mailTo,
                       int maxDeliveryDays, String product, String applePart, int devices, Instant lastTickAt, Instant now, List<Row> stores) {}

    public Feed feed() {
        Map<String, StoreStateEntity> rows = new java.util.HashMap<>();
        for (StoreStateEntity row : StoreStateEntity.<StoreStateEntity>listAll()) rows.put(row.storeKey, row);
        List<Row> list = new ArrayList<>();
        for (Store store : StockRadarStores.STORES) {
            StoreStateEntity row = rows.get(store.key());
            list.add(new Row(store.key(), store.name(), store.kindLabel(), store.method().name().toLowerCase(java.util.Locale.ROOT), store.url(), store.orderUrl(), interval(store),
                    row == null ? null : row.available, row == null ? null : row.inStock, row == null ? null : row.statusText,
                    row == null ? null : row.deliveryBy, row == null ? null : row.priceEur, row == null ? null : row.foundUrl,
                    row == null ? null : row.checkedAt, row == null ? null : row.changedAt, row == null ? null : row.notifiedAt,
                    nextCheck.get(store.key()), row == null ? null : row.lastError,
                    row == null || row.errorCount == null ? 0 : row.errorCount, row == null || row.checkCount == null ? 0 : row.checkCount,
                    row == null ? null : row.lastPushStatus, row == null ? null : row.lastMailStatus));
        }
        int devices;
        try { devices = push.deviceCount(notifyUser); } catch (RuntimeException failure) { devices = 0; }
        return new Feed(enabled, pushEnabled, mailEnabled, browserEnabled, notifyUser, mailTo, maxDeliveryDays, StockRadarStores.PRODUCT,
                StockRadarStores.APPLE_PART, devices, lastTickAt, Instant.now(), list);
    }

    /** "Nu controleren": every shop, or one; browser shops finish in the background. */
    public void checkNow(String key) {
        List<Store> stores = key == null ? StockRadarStores.STORES : List.of(java.util.Objects.requireNonNull(StockRadarStores.byKey(key)));
        run(stores, Instant.now());
    }

    static String formatEuro(BigDecimal amount) {
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(java.util.Locale.of("nl", "BE"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }

    private static String hostOf(String url) {
        try { return URI(url); } catch (RuntimeException e) { return url; }
    }

    private static String URI(String url) {
        java.net.URI uri = java.net.URI.create(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String compact = uri.getHost() + (path.length() > 40 ? path.substring(0, 40) + "…" : path);
        return compact.replaceFirst("^www\\.", "");
    }
}
