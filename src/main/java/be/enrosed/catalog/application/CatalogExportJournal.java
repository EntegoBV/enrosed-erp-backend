package be.enrosed.catalog.application;

import be.enrosed.catalog.adapter.out.persistence.CatalogExportEntity;
import be.enrosed.catalog.adapter.out.persistence.CatalogExportLineEntity;
import be.enrosed.catalog.application.port.out.CatalogDocumentRenderer;
import be.enrosed.catalog.domain.Product;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityChangeSet;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Which customer saw which price: every catalogue PDF made with prices or for a recipient, with
 * one line per product and the price it printed. A bean of its own, so the write gets its own
 * short transaction after the render (which can take minutes and runs outside one); a failed
 * write fails the export, so no priced PDF leaves unrecorded. For a stored customer with prices
 * the Logboek says "Catalogus met prijzen gemaakt"; the ERP only knows the PDF was made.
 */
@ApplicationScoped
public class CatalogExportJournal {

    public static final String ACTIVITY_ENTITY = "CUSTOMER";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    public record Header(Long id, Instant exportedAt, String exportedBy, Long customerId, String customerName,
                         String language, String layout, boolean includePrices, LocalDate pricesValidUntil,
                         int productCount, int customerPriceCount, int oneOffCount, int onRequestCount,
                         String fileName, String pdfSha256) {}

    public record Line(Long productId, String sku, String name, BigDecimal printedPriceEur,
                       BigDecimal cataloguePriceEur, BigDecimal customerPriceEur, String priceSource,
                       String salesUnit, Integer piecesPerUnit, String unitKey) {}

    /** The header fields with the lines, flat, as the studio's detail sheet reads them. */
    public record Detail(Long id, Instant exportedAt, String exportedBy, Long customerId, String customerName,
                         String language, String layout, boolean includePrices, LocalDate pricesValidUntil,
                         int productCount, int customerPriceCount, int oneOffCount, int onRequestCount,
                         String fileName, String pdfSha256, List<Line> lines) {}

    private final EntityManager entities;
    private final CurrentActor actor;
    private final ActivityLogService activity;
    private final AdminIdentityProvider names;

    public CatalogExportJournal(EntityManager entities, CurrentActor actor, ActivityLogService activity,
                                AdminIdentityProvider names) {
        this.entities = entities;
        this.actor = actor;
        this.activity = activity;
        this.names = names;
    }

    @Transactional
    public long record(CatalogExportService.Model model, CatalogDocumentRenderer.Document document) {
        CatalogExportService.Request request = model.request();
        CatalogExportService.PriceBook prices = model.prices();
        Language language = Language.of(request.language());
        ActorRef who = actor.current();

        CatalogExportEntity export = new CatalogExportEntity();
        export.exportedAt = Instant.now();
        export.exportedBy = who.username();
        export.exportedByName = who.displayName();
        export.customerId = model.recipient() == null ? null : model.recipient().customerId();
        export.customerName = model.recipient() == null ? null : model.recipient().name();
        export.layout = request.resolvedLayout().name();
        export.language = language.code();
        export.includePrices = request.includePrices();
        export.pricesValidUntil = request.includePrices() ? request.pricesValidUntil() : null;
        export.productCount = model.products().size();
        export.customerPriceCount = (int) prices.count(CatalogExportService.PriceSource.CUSTOMER);
        export.oneOffCount = prices.oneOffCount();
        export.onRequestCount = (int) prices.count(CatalogExportService.PriceSource.ON_REQUEST);
        export.fileName = document.filename();
        export.pdfBytes = document.content().length;
        export.pdfSha256 = sha256(document.content());
        entities.persist(export);

        int position = 0;
        for (Product product : model.products()) {
            CatalogExportService.ResolvedPrice price = prices.of(product);
            CatalogExportLineEntity line = new CatalogExportLineEntity();
            line.exportId = export.id;
            line.position = position++;
            line.productId = product.id();
            line.sku = clip(product.sku(), 255);
            line.name = clip(product.nameIn(language), 255);
            line.salesUnit = product.packaging().salesUnit().name();
            line.piecesPerUnit = price == null ? null : price.piecesPerUnit();
            line.unitKey = product.packaging().unitKey();
            line.cataloguePriceEur = positive(product.computedSalesPriceEur());
            line.customerPriceEur = price == null ? null : price.customerEur();
            line.printedPriceEur = price == null ? null : price.printedEur();
            line.priceSource = price == null ? null : price.source().name();
            entities.persist(line);
        }

        if (export.customerId != null && export.includePrices) {
            activity.record(ActivityLogService.ACTION_CATALOG_EXPORTED, ACTIVITY_ENTITY, export.customerId.toString(), export.customerName,
                    summary(export), ActivityChangeSet.create()
                            .add("fileName", "Bestand", null, export.fileName)
                            .add("pricesValidUntil", "Geldig tot", null, export.pricesValidUntil)
                            .build());
        }
        return export.id;
    }

    /** Newest first; a deleted customer's exports stay readable under the name they were made for. */
    public List<Header> forCustomer(long customerId, int limit) {
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return entities.createQuery("from CatalogExportEntity e where e.customerId = :customer"
                        + " order by e.exportedAt desc, e.id desc", CatalogExportEntity.class)
                .setParameter("customer", customerId).setMaxResults(size).getResultList().stream()
                .map(this::header).toList();
    }

    public Detail detail(long id) {
        CatalogExportEntity export = entities.find(CatalogExportEntity.class, id);
        if (export == null) throw new NotFoundException("Catalogusexport", id);
        List<Line> lines = entities.createQuery("from CatalogExportLineEntity l where l.exportId = :export"
                        + " order by l.position, l.id", CatalogExportLineEntity.class)
                .setParameter("export", id).getResultList().stream()
                .map(line -> new Line(line.productId, line.sku, line.name, line.printedPriceEur,
                        line.cataloguePriceEur, line.customerPriceEur, line.priceSource, line.salesUnit,
                        line.piecesPerUnit, line.unitKey))
                .toList();
        Header header = header(export);
        return new Detail(header.id(), header.exportedAt(), header.exportedBy(), header.customerId(),
                header.customerName(), header.language(), header.layout(), header.includePrices(),
                header.pricesValidUntil(), header.productCount(), header.customerPriceCount(),
                header.oneOffCount(), header.onRequestCount(), header.fileName(), header.pdfSha256(), lines);
    }

    private Header header(CatalogExportEntity export) {
        String by = export.exportedByName != null && !export.exportedByName.isBlank()
                ? export.exportedByName : names.displayNameFor(export.exportedBy);
        return new Header(export.id, export.exportedAt, by, export.customerId, export.customerName,
                export.language, export.layout, export.includePrices, export.pricesValidUntil,
                export.productCount, export.customerPriceCount, export.oneOffCount, export.onRequestCount,
                export.fileName, export.pdfSha256);
    }

    /** "Catalogus met prijzen gemaakt · 24 producten, 3 klantprijzen, 2 eenmalig · Handelscatalogus NL". */
    private static String summary(CatalogExportEntity export) {
        StringBuilder summary = new StringBuilder("Catalogus met prijzen gemaakt · ")
                .append(export.productCount).append(export.productCount == 1 ? " product" : " producten");
        if (export.customerPriceCount > 0) {
            summary.append(", ").append(export.customerPriceCount)
                    .append(export.customerPriceCount == 1 ? " klantprijs" : " klantprijzen");
        }
        if (export.oneOffCount > 0) summary.append(", ").append(export.oneOffCount).append(" eenmalig");
        return summary.append(" · ")
                .append("BROCHURE".equals(export.layout) ? "Handelscatalogus" : "Compact overzicht")
                .append(' ').append(export.language.toUpperCase(Locale.ROOT)).toString();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 ontbreekt", impossible);
        }
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static String clip(String value, int length) {
        if (value == null) return null;
        String text = value.strip();
        return text.length() <= length ? text : text.substring(0, length);
    }
}
