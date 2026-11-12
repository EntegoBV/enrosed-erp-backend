package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerCustomerLinkEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportLineEntity;
import be.enrosed.partnerportal.domain.PartnerCountUnits;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.partnerportal.domain.PartnerSaleTexts;
import be.enrosed.sales.application.NotificationService;
import be.enrosed.sales.application.PartnerSaleNotificationFeed;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The partner portal's bell items.
 *
 * <p>PARTNER_VERKOOP: one per non-voided melding made or edited in the last 14 days (by greatest(created_at,
 * updated_at), which is also its time). News, never work (actionNeeded false); orderId stays null so an older
 * screen never opens /sales/&lt;id&gt;; the melding is in url "/veilingverkopen?melding=&lt;id&gt;";
 * notificationId "partner-sale:&lt;id&gt;" until it is edited, then "partner-sale:&lt;id&gt;:&lt;editToken&gt;",
 * so an edit comes back as a new item; its detail then ends in "gewijzigd". open = it still has a part to
 * invoice (the badge counts those).
 *
 * <p>PARTNER_KLANT: one per customer a partner added itself that staff have not marked reviewed ("Nagekeken"
 * on the customer sheet). Work (actionNeeded true), orderId null, url "/customers?klant=&lt;id&gt;",
 * notificationId "partner-customer:&lt;id&gt;", at = when it was added.
 */
@ApplicationScoped
public class PartnerSaleNotifications implements PartnerSaleNotificationFeed {

    static final Duration WINDOW = Duration.ofDays(14);

    private final PartnerPortalRepositories.Reports reports;
    private final PartnerReportReader reader;
    private final ProductRepository products;
    private final SalesRepositories.Customers customers;
    private final PartnerPortalRepositories.CustomerLinks links;

    public PartnerSaleNotifications(PartnerPortalRepositories.Reports reports, PartnerReportReader reader,
                                    ProductRepository products, SalesRepositories.Customers customers,
                                    PartnerPortalRepositories.CustomerLinks links) {
        this.reports = reports;
        this.reader = reader;
        this.products = products;
        this.customers = customers;
        this.links = links;
    }

    @Override
    @Transactional
    public List<NotificationService.Notification> feed(Instant now) {
        Map<Long, String> customerNames = new HashMap<>();
        List<NotificationService.Notification> result = new ArrayList<>(meldingen(now, customerNames));
        result.addAll(newCustomers(customerNames));
        return result;
    }

    /** PARTNER_KLANT: the customers partners added that staff have not reviewed yet. */
    private List<NotificationService.Notification> newCustomers(Map<Long, String> customerNames) {
        List<NotificationService.Notification> result = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (PartnerCustomerLinkEntity link : links.awaitingReview()) {
            if (link.customerId == null || !seen.add(link.customerId)) continue;
            Optional<Customer> customer = customers.findById(link.customerId);
            if (customer.isEmpty()) continue;
            String partner = link.partnerCustomerId == null ? null : customerNames.computeIfAbsent(link.partnerCustomerId,
                    id -> customers.findById(id).map(Customer::company).orElse(null));
            String who = link.createdByName == null || link.createdByName.isBlank() ? "de partner" : link.createdByName;
            result.add(new NotificationService.Notification(NotificationService.Kind.PARTNER_KLANT, null, null,
                    customer.get().company(), "Nieuwe klant via het partnerportaal",
                    "Aangemaakt door " + who + (partner == null ? "" : " (" + partner + ")") + " · vul de gegevens aan",
                    true, link.createdAt, "/customers?klant=" + link.customerId, "partner-customer:" + link.customerId));
        }
        return result;
    }

    /** PARTNER_VERKOOP: the meldingen made or edited in the window. */
    private List<NotificationService.Notification> meldingen(Instant now, Map<Long, String> customerNames) {
        List<PartnerSaleReportEntity> recent = reports.liveTouchedSince(now.minus(WINDOW));
        if (recent.isEmpty()) return List.of();
        List<PartnerReportReader.Loaded> loaded = reader.load(recent);
        Map<Long, Optional<Product>> productCache = new HashMap<>();
        List<NotificationService.Notification> result = new ArrayList<>();
        for (PartnerReportReader.Loaded item : loaded) {
            PartnerSaleReportEntity report = item.report();
            String customer = report.customerId == null ? null : customerNames.computeIfAbsent(report.customerId,
                    id -> customers.findById(id).map(Customer::company).orElse(null));
            int saleQuantity = 0;
            int buybackQuantity = 0;
            int salePieces = 0;
            int buybackPieces = 0;
            BigDecimal saleNet = BigDecimal.ZERO;
            BigDecimal buyback = BigDecimal.ZERO;
            BigDecimal saleUnit = null;
            BigDecimal buybackUnit = null;
            Set<Long> productIds = new LinkedHashSet<>();
            for (PartnerSaleReportLineEntity line : item.lines()) {
                int factor = line.productId == null ? 1 : factor(productCache, line.productId);
                if (line.isSale()) {
                    saleQuantity += line.quantityOrZero();
                    salePieces += line.quantityOrZero() * factor;
                    if (line.netEur != null) saleNet = saleNet.add(line.netEur);
                    if (saleUnit == null) saleUnit = line.unitPriceEur;
                } else if (line.isBuyback()) {
                    buybackQuantity += line.quantityOrZero();
                    buybackPieces += line.quantityOrZero() * factor;
                    /* The terugkoopkost: -net (round 2 adds the buyback's commission and transport to its gross). */
                    buyback = buyback.add(PartnerSaleMoney.buybackCost(line.grossEur, line.netEur));
                    if (buybackUnit == null) buybackUnit = line.unitPriceEur;
                } else {
                    continue;
                }
                if (line.productId != null) productIds.add(line.productId);
            }
            boolean buybackOnly = saleQuantity == 0 && buybackQuantity > 0;
            BigDecimal net = saleNet.subtract(buyback);
            String detail;
            /* In pieces with the piece noun and per-piece averages ("120 rozen · gem. € 1,20"). */
            if (productIds.size() == 1) {
                long productId = productIds.iterator().next();
                PartnerCountUnits.Counting counting = PartnerCountUnits.of(
                        productCache.computeIfAbsent(productId, products::findById).orElse(null));
                int factor = counting.piecesPerSalesUnit();
                detail = buybackOnly
                        ? counting.pieces(buybackPieces) + " · klokprijs "
                        + PartnerSaleTexts.unitEuro(perPiece(buybackUnit, factor)) + " · terugkoop "
                        + PartnerSaleTexts.euro(buyback)
                        : counting.pieces(salePieces) + " · gem. "
                        + PartnerSaleTexts.unitEuro(perPiece(saleUnit, factor)) + " · netto " + PartnerSaleTexts.euro(net);
            } else {
                detail = (buybackOnly ? buybackPieces : salePieces) + " stuks · " + productIds.size()
                        + " producten · " + (buybackOnly ? "terugkoop " + PartnerSaleTexts.euro(buyback)
                        : "netto " + PartnerSaleTexts.euro(net));
            }
            boolean open = PartnerPortalRules.STATUS_OPEN.equals(item.status())
                    || PartnerPortalRules.STATUS_PARTLY_INVOICED.equals(item.status());
            boolean edited = report.updatedAt != null;
            Instant at = edited && (report.createdAt == null || report.updatedAt.isAfter(report.createdAt))
                    ? report.updatedAt : report.createdAt;
            result.add(new NotificationService.Notification(NotificationService.Kind.PARTNER_VERKOOP, null,
                    PartnerPortalRules.reportNumber(report.id), customer,
                    PartnerSaleTexts.title(report.channel, buybackOnly), edited ? detail + " · gewijzigd" : detail,
                    false, at, "/veilingverkopen?melding=" + report.id,
                    "partner-sale:" + report.id + (edited ? ":" + report.editToken() : ""), open));
        }
        return result;
    }

    private int factor(Map<Long, Optional<Product>> cache, long productId) {
        return PartnerCountUnits.of(cache.computeIfAbsent(productId, products::findById).orElse(null)).piecesPerSalesUnit();
    }

    private static BigDecimal perPiece(BigDecimal unitPrice, int factor) {
        return unitPrice == null ? null : unitPrice.divide(BigDecimal.valueOf(Math.max(1, factor)), 4, java.math.RoundingMode.HALF_UP);
    }
}
