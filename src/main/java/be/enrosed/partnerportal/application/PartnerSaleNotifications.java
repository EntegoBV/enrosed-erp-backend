package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Product;
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
 * The bell's PARTNER_VERKOOP items: one per non-voided melding of the last 14 days. News, never
 * work (actionNeeded false); orderId stays null so an older screen never opens /sales/&lt;id&gt;;
 * the melding is in url "/veilingverkopen?melding=&lt;id&gt;" and notificationId "partner-sale:&lt;id&gt;";
 * open = it still has a part to invoice (the badge counts those).
 */
@ApplicationScoped
public class PartnerSaleNotifications implements PartnerSaleNotificationFeed {

    static final Duration WINDOW = Duration.ofDays(14);

    private final PartnerPortalRepositories.Reports reports;
    private final PartnerReportReader reader;
    private final ProductRepository products;
    private final SalesRepositories.Customers customers;

    public PartnerSaleNotifications(PartnerPortalRepositories.Reports reports, PartnerReportReader reader,
                                    ProductRepository products, SalesRepositories.Customers customers) {
        this.reports = reports;
        this.reader = reader;
        this.products = products;
        this.customers = customers;
    }

    @Override
    @Transactional
    public List<NotificationService.Notification> feed(Instant now) {
        List<PartnerSaleReportEntity> recent = reports.liveCreatedSince(now.minus(WINDOW));
        if (recent.isEmpty()) return List.of();
        List<PartnerReportReader.Loaded> loaded = reader.load(recent);
        Map<Long, Optional<Product>> productCache = new HashMap<>();
        Map<Long, String> customerNames = new HashMap<>();
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
            result.add(new NotificationService.Notification(NotificationService.Kind.PARTNER_VERKOOP, null,
                    PartnerPortalRules.reportNumber(report.id), customer,
                    PartnerSaleTexts.title(report.channel, buybackOnly), detail, false, report.createdAt,
                    "/veilingverkopen?melding=" + report.id, "partner-sale:" + report.id, open));
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
