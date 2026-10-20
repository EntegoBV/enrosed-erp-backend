package be.enrosed.tica;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.catalog.application.ProductChannelPriceService;
import be.enrosed.push.WebPushNotifier;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.tica.TicaEntities.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class TicaService {
    static final ZoneId ZONE = ZoneId.of("Europe/Brussels");
    private final EntityManager entities;
    private final WebPushNotifier push;
    private final TicaStockService stock;
    private final ProductChannelPriceService channelPrices;
    @ConfigProperty(name = "enrosed.tica.enabled", defaultValue = "false") boolean enabled;
    @ConfigProperty(name = "enrosed.tica.username", defaultValue = "TT_2132") String username;
    @ConfigProperty(name = "enrosed.tica.password") Optional<String> password;

    public TicaService(EntityManager entities, WebPushNotifier push, TicaStockService stock,
                       ProductChannelPriceService channelPrices) {
        this.entities = entities; this.push = push; this.stock = stock; this.channelPrices = channelPrices;
    }

    /** Singleton seeding is retried outside the failed transaction when two instances start together. */
    void ensureSettings() {
        if (QuarkusTransaction.requiringNew().call(() -> Settings.count()) != 0) return;
        try {
            QuarkusTransaction.requiringNew().run(() -> { if (Settings.count() == 0) new Settings().persistAndFlush(); });
        } catch (RuntimeException exception) {
            if (QuarkusTransaction.requiringNew().call(() -> Settings.count()) == 0) throw exception;
        }
    }

    boolean credentialsReady() { return username != null && !username.isBlank() && password != null && password.filter(p -> !p.isBlank()).isPresent(); }
    static Settings lockedSettings() { return Settings.findById(1L, LockModeType.PESSIMISTIC_WRITE); }

    public record SettingsDto(NotificationMode notificationMode, String summaryTime, String timezone) {}
    public record StatusDto(boolean enabled, boolean credentialsReady, String syncState, Instant lastAttemptAt,
                            Instant lastSuccessAt, LocalDate lastRangeFrom, LocalDate lastRangeTo, String lastError,
                            long importedLineCount, long unmatchedLineCount, long pushSubscriptions,
                            String lastNotificationStatus, Instant lastNotificationAt) {}
    public record SaleDto(Long id, String location, String exhibitor, LocalDateTime soldAt, String transactionNumber,
                          String lineNumber, String description, String beeyondArticleNumber, String sku, String barcode,
                          BigDecimal quantity, BigDecimal discountPercent, BigDecimal grossTotal, BigDecimal netTotal,
                          BigDecimal vatRate, String transactionType, Long productId, String mappingStatus,
                          Instant firstSeenAt, Instant updatedAt, String stockStatus, String stockMessage,
                          Long stockProductId, Long stockLocationId, int stockQuantity, Instant stockUpdatedAt,
                          int stockPieces, Integer stockUnitPieces, String stockSourceUnit) {}
    public record SalesDto(LocalDate from, LocalDate to, int page, int size, long totalLines, long transactionCount,
                           BigDecimal quantity, BigDecimal grossTotal, BigDecimal netTotal, long unmatchedLineCount,
                           List<SaleDto> lines) {}
    public record AnalyticsDay(LocalDate date, long transactionCount, BigDecimal quantity, BigDecimal revenueExcl,
                               BigDecimal goodsCostEur, long missingCostLines) {}
    record Claim(String token, LocalDate from, LocalDate to, boolean initial, Instant startedAt) {}

    public StatusDto status() {
        ensureSettings();
        return QuarkusTransaction.requiringNew().call(() -> {
            Settings settings = Settings.findById(1L);
            String state = !enabled ? "DISABLED" : !credentialsReady() ? "NOT_CONFIGURED"
                    : settings.leaseUntil != null && settings.leaseUntil.isAfter(Instant.now()) ? "RUNNING"
                    : settings.syncRequested ? "PENDING" : settings.lastError != null ? "ERROR"
                    : settings.lastSuccessAt == null ? "NEVER_SYNCED" : "READY";
            Notice last = Notice.<Notice>find("order by id desc").firstResult();
            return new StatusDto(enabled, credentialsReady(), state, settings.lastAttemptAt, settings.lastSuccessAt,
                    settings.lastRangeFrom, settings.lastRangeTo, settings.lastError, Sale.count("active = true"),
                    Sale.count("active = true and productId is null"), push.subscriptionCount(), last == null ? null : last.status,
                    last == null ? null : last.acceptedAt);
        });
    }

    public SettingsDto settings() {
        ensureSettings();
        return QuarkusTransaction.requiringNew().call(() -> dto(Settings.<Settings>findById(1L)));
    }

    public SettingsDto updateSettings(SettingsDto request) {
        if (request == null || request.notificationMode() == null || request.summaryTime() == null
                || !request.summaryTime().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
            throw new BadRequestException("Kies een geldige meldingsmodus en tijd (HH:mm).");
        ensureSettings();
        return QuarkusTransaction.requiringNew().call(() -> {
            Settings row = lockedSettings();
            row.notificationMode = request.notificationMode(); row.summaryTime = request.summaryTime();
            // Disabling a mode also cancels its outstanding notices, including retries with no devices.
            List<Notice> pending = Notice.list("status in ?1", List.of("PENDING", "WAITING_FOR_DEVICES", "PARTIAL"));
            for (Notice notice : pending) {
                if ((notice.kind.equals("daily") && !row.notificationMode.daily())
                        || (notice.kind.equals("sale") && !row.notificationMode.sales())) notice.status = "CANCELLED";
            }
            return dto(row);
        });
    }

    public void requestSync() {
        if (!enabled || !credentialsReady()) throw new BusinessRuleException("TICA is nog niet ingesteld op de server.");
        ensureSettings();
        QuarkusTransaction.requiringNew().run(() -> lockedSettings().syncRequested = true);
    }

    Claim claim(Instant now) {
        if (!enabled || !credentialsReady()) return null;
        ensureSettings();
        return QuarkusTransaction.requiringNew().call(() -> {
            Settings settings = lockedSettings();
            if (settings.leaseUntil != null && settings.leaseUntil.isAfter(now)) return null;
            LocalDate today = now.atZone(ZONE).toLocalDate();
            Instant summaryAt = today.atTime(LocalTime.parse(settings.summaryTime)).atZone(ZONE).toInstant();
            boolean digestNeedsRefresh = settings.notificationMode.daily() && !now.isBefore(summaryAt)
                    && (settings.lastAttemptAt == null || settings.lastAttemptAt.isBefore(summaryAt));
            if (!settings.syncRequested && !digestNeedsRefresh && settings.lastAttemptAt != null
                    && settings.lastAttemptAt.isAfter(now.minusSeconds(15 * 60))) return null;
            settings.syncRequested = false;
            settings.leaseToken = UUID.randomUUID().toString(); settings.leaseUntil = now.plusSeconds(300);
            settings.lastAttemptAt = now;
            return new Claim(settings.leaseToken, today.minusDays(settings.lastSuccessAt == null ? 29 : 6),
                    today, settings.lastSuccessAt == null, now);
        });
    }

    /** One locked transaction commits all validated lines, counters and outbox entries together. */
    @Transactional
    public void complete(Claim claim, List<TicaCsvParser.Row> rows, Instant now) {
        Settings settings = lockedSettings();
        if (!Objects.equals(settings.leaseToken, claim.token())) return; // stale worker must never overwrite a later run
        Set<String> newTransactions = new LinkedHashSet<>();
        Set<String> present = new HashSet<>();
        Map<String, Long> products = new HashMap<>();
        for (TicaCsvParser.Row source : rows) {
            present.add(source.key());
            Sale sale = Sale.<Sale>find("exhibitor = ?1 and location = ?2 and transactionNumber = ?3 and lineNumber = ?4",
                    source.exhibitor(), source.location(), source.transactionNumber(), source.lineNumber()).firstResult();
            boolean added = sale == null;
            if (added) { sale = new Sale(); sale.firstSeenAt = now; }
            Long productId = null;
            if (!source.sku().isBlank()) {
                if (!products.containsKey(source.sku())) {
                    List<Long> ids = entities.createQuery("select p.id from ProductEntity p where p.sku = :sku", Long.class)
                            .setParameter("sku", source.sku()).setMaxResults(2).getResultList();
                    products.put(source.sku(), ids.size() == 1 ? ids.getFirst() : null);
                }
                productId = products.get(source.sku());
            }
            boolean changed = added || !sale.active || !source.equals(source(sale)) || !Objects.equals(productId, sale.productId);
            sale.location = source.location(); sale.exhibitor = source.exhibitor(); sale.soldAt = source.soldAt();
            sale.transactionNumber = source.transactionNumber(); sale.lineNumber = source.lineNumber();
            sale.description = source.description(); sale.beeyondArticleNumber = source.beeyondArticleNumber();
            sale.sku = source.sku(); sale.barcode = source.barcode(); sale.quantity = source.quantity();
            sale.discountPercent = source.discountPercent(); sale.grossTotal = source.grossTotal(); sale.netTotal = source.netTotal();
            sale.vatRate = source.vatRate(); sale.transactionType = source.transactionType(); sale.productId = productId; sale.active = true;
            if (changed) sale.updatedAt = now;
            if (added) {
                sale.persist();
                if (!claim.initial() && source.soldAt().toLocalDate().equals(claim.to())) newTransactions.add(source.transactionKey());
            }
        }
        // Only a validated complete snapshot can retract disappeared source lines; a failed sync never does.
        List<Sale> previous = Sale.list("active = true and soldAt >= ?1 and soldAt < ?2",
                claim.from().atStartOfDay(), claim.to().plusDays(1).atStartOfDay());
        for (Sale sale : previous) {
            if (!present.contains(source(sale).key())) { sale.active = false; sale.updatedAt = now; }
        }
        Sale.flush();
        stock.refreshProductMappings(now);
        stock.lockRelevantProducts();
        channelPrices.refreshTicaPrices();
        stock.reconcileAll(now);
        settings.lastSuccessAt = now; settings.lastRangeFrom = claim.from(); settings.lastRangeTo = claim.to();
        if (settings.coverageFrom == null || settings.coverageTo == null || claim.from().isAfter(settings.coverageTo.plusDays(1))
                || claim.to().isBefore(settings.coverageFrom.minusDays(1))) {
            settings.coverageFrom = claim.from(); settings.coverageTo = claim.to();
        } else {
            if (claim.from().isBefore(settings.coverageFrom)) settings.coverageFrom = claim.from();
            if (claim.to().isAfter(settings.coverageTo)) settings.coverageTo = claim.to();
        }
        settings.lastError = null; settings.leaseToken = null; settings.leaseUntil = null;
        if (settings.notificationMode.sales()) {
            for (String key : newTransactions) {
                List<TicaCsvParser.Row> transaction = rows.stream().filter(r -> r.transactionKey().equals(key)).toList();
                enqueue("sale:" + key, "sale", "Nieuwe TICA-verkoop", summary(transaction), now);
            }
        }
        LocalDate today = now.atZone(ZONE).toLocalDate();
        Instant summaryAt = today.atTime(LocalTime.parse(settings.summaryTime)).atZone(ZONE).toInstant();
        // A run started before the configured cutoff is never fresh enough for the digest.
        if (settings.notificationMode.daily() && claim.to().equals(today) && !claim.startedAt().isBefore(summaryAt)) {
            List<TicaCsvParser.Row> todaysRows = rows.stream().filter(r -> r.soldAt().toLocalDate().equals(today)).toList();
            if (!todaysRows.isEmpty()) enqueue("daily:" + today, "daily", "TICA · dagoverzicht " + today,
                    summary(todaysRows), now);
        }
    }

    @Transactional
    public void failed(Claim claim, String safeError) {
        Settings row = lockedSettings();
        if (!Objects.equals(row.leaseToken, claim.token())) return;
        row.leaseUntil = null; row.leaseToken = null; row.lastError = safeError;
    }

    @Transactional
    public SalesDto sales(LocalDate from, LocalDate to, int page, int size) {
        LocalDate today = LocalDate.now(ZONE);
        if (from == null) from = today.minusDays(29);
        if (to == null) to = today;
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 365
                || page < 0 || page > 100_000 || size < 1 || size > 200)
            throw new BadRequestException("Kies maximaal 366 dagen en een geldige paginagrootte (1–200).");
        LocalDateTime start = from.atStartOfDay(), end = to.plusDays(1).atStartOfDay();
        String where = "active = true and soldAt >= ?1 and soldAt < ?2";
        List<Sale> all = Sale.list(where, start, end);
        List<SaleDto> lines = Sale.<Sale>find(where + " order by soldAt desc, id desc", start, end).page(page, size).list().stream().map(TicaService::dto).toList();
        return new SalesDto(from, to, page, size, all.size(), all.stream().map(s -> s.exhibitor + "|" + s.location + "|" + s.transactionNumber).distinct().count(),
                all.stream().map(s -> s.quantity).reduce(BigDecimal.ZERO, BigDecimal::add),
                all.stream().map(s -> s.grossTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                all.stream().map(s -> s.netTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                all.stream().filter(s -> s.productId == null).count(), lines);
    }

    private static void enqueue(String key, String kind, String title, String body, Instant now) {
        if (Notice.count("noticeKey", key) != 0) return;
        Notice notice = new Notice(); notice.noticeKey = key; notice.kind = kind; notice.title = title; notice.body = body;
        notice.createdAt = now; notice.nextAttemptAt = now;
        notice.expiresAt = now.atZone(ZONE).toLocalDate().plusDays(1).atTime(12, 0).atZone(ZONE).toInstant();
        notice.persist();
    }

    private static String summary(List<TicaCsvParser.Row> rows) {
        BigDecimal quantity = rows.stream().map(TicaCsvParser.Row::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = rows.stream().map(TicaCsvParser.Row::netTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        long transactions = rows.stream().map(TicaCsvParser.Row::transactionKey).distinct().count();
        return transactions + " transactie(s) · " + quantity.stripTrailingZeros().toPlainString().replace('.', ',')
                + " stuks · € " + total.setScale(2).toPlainString().replace('.', ',') + " omzet excl. btw";
    }

    /** Revenue once, with a clearly separate current product-cost estimate (not historical inventory postings). */
    @Transactional public List<AnalyticsDay> analytics() {
        List<Sale> sales = Sale.list("active = true order by soldAt");
        Set<Long> ids = new HashSet<>(); for (Sale sale : sales) if (sale.productId != null) ids.add(sale.productId);
        Map<Long, BigDecimal> unitCosts = new HashMap<>();
        if (!ids.isEmpty()) for (ProductEntity product : entities.createQuery("from ProductEntity where id in :ids", ProductEntity.class)
                .setParameter("ids", ids).getResultList()) {
            String unit = stock.sourceUnit(product);
            if (product.landedCostEur != null && unit != null) {
                BigDecimal cost = product.landedCostEur;
                if (product.packaging().soldAsDisplay() && unit.equals("PIECE"))
                    cost = cost.divide(BigDecimal.valueOf(product.packaging().unitPieces()), 8, java.math.RoundingMode.HALF_UP);
                unitCosts.put(product.id, cost);
            }
        }
        Map<LocalDate, List<Sale>> days = new TreeMap<>();
        for (Sale sale : sales) days.computeIfAbsent(sale.soldAt.toLocalDate(), ignored -> new ArrayList<>()).add(sale);
        return days.entrySet().stream().map(entry -> {
            List<Sale> rows = entry.getValue();
            BigDecimal cost = rows.stream().filter(s -> unitCosts.containsKey(s.productId))
                    .map(s -> unitCosts.get(s.productId).multiply(s.quantity)).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new AnalyticsDay(entry.getKey(), rows.stream().map(s -> s.exhibitor + "|" + s.location + "|" + s.transactionNumber).distinct().count(),
                    rows.stream().map(s -> s.quantity).reduce(BigDecimal.ZERO, BigDecimal::add),
                    rows.stream().map(s -> s.netTotal).reduce(BigDecimal.ZERO, BigDecimal::add), cost,
                    rows.stream().filter(s -> !unitCosts.containsKey(s.productId)).count());
        }).toList();
    }

    private static SettingsDto dto(Settings s) { return new SettingsDto(s.notificationMode, s.summaryTime, ZONE.getId()); }
    private static SaleDto dto(Sale s) { return new SaleDto(s.id, s.location, s.exhibitor, s.soldAt, s.transactionNumber,
            s.lineNumber, s.description, s.beeyondArticleNumber, s.sku, s.barcode, s.quantity, s.discountPercent,
            s.grossTotal, s.netTotal, s.vatRate, s.transactionType, s.productId,
            s.productId == null ? "UNMATCHED" : "MATCHED", s.firstSeenAt, s.updatedAt,
            TicaStockService.status(s), TicaStockService.message(s), s.stockProductId, s.stockLocationId,
            TicaStockService.quantity(s), s.stockUpdatedAt, TicaStockService.pieces(s),
            s.stockUnitPieces, s.stockSourceUnit); }
    private static TicaCsvParser.Row source(Sale s) { return new TicaCsvParser.Row(s.location, s.exhibitor, s.soldAt,
            s.transactionNumber, s.lineNumber, s.description, s.beeyondArticleNumber, s.sku, s.barcode, s.quantity,
            s.discountPercent, s.grossTotal, s.netTotal, s.vatRate, s.transactionType); }
}
