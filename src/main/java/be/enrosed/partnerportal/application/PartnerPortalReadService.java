package be.enrosed.partnerportal.application;

import be.enrosed.catalog.application.SalesPhotoService;
import be.enrosed.catalog.application.StockService;
import be.enrosed.catalog.application.port.out.ProductRepository;
import be.enrosed.catalog.domain.Photo;
import be.enrosed.catalog.domain.Product;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerLoginEntity;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerPortalRepositories;
import be.enrosed.partnerportal.adapter.out.persistence.PartnerSaleReportEntity;
import be.enrosed.partnerportal.domain.PartnerNumbers;
import be.enrosed.partnerportal.domain.PartnerPortalRules;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.Customer;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.StaffContactService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the partner sees: the overview (stock at its location, what it sold lately, its last
 * meldingen, the cost percents and whom to call), the melding pages and the product photos.
 * The customer id always comes from the signed-in identity, never from the request.
 */
@ApplicationScoped
public class PartnerPortalReadService {

    static final int OVERVIEW_REPORTS = 10;

    /** The photo's cache identity, and the image itself only when it is really needed (no 304). */
    public record PhotoAnswer(String etag, Supplier<SalesPhotoService.Image> image) {}

    private final PartnerPortalSettings settings;
    private final PartnerProductTiles tiles;
    private final PartnerReportReader reports;
    private final StockService stock;
    private final StaffContactService contacts;
    private final SalesRepositories.Customers customers;
    private final PartnerPortalRepositories.Logins logins;
    private final PartnerPortalRepositories.Lines lines;
    private final SalesPhotoService photos;
    private final ProductRepository productRepository;
    private final PartnerPortalRepositories.Reports reportRows;

    public PartnerPortalReadService(PartnerPortalSettings settings, PartnerProductTiles tiles,
                                    PartnerReportReader reports, StockService stock, StaffContactService contacts,
                                    SalesRepositories.Customers customers, PartnerPortalRepositories.Logins logins,
                                    PartnerPortalRepositories.Lines lines, SalesPhotoService photos,
                                    ProductRepository productRepository,
                                    PartnerPortalRepositories.Reports reportRows) {
        this.reportRows = reportRows;
        this.settings = settings;
        this.tiles = tiles;
        this.reports = reports;
        this.stock = stock;
        this.contacts = contacts;
        this.customers = customers;
        this.logins = logins;
        this.lines = lines;
        this.photos = photos;
        this.productRepository = productRepository;
    }

    @Transactional
    public PartnerPortalViews.Overview overview(long customerId, long loginId) {
        Customer customer = customers.findById(customerId).orElseThrow(() -> new NotFoundException("Klant", customerId));
        PartnerLoginEntity login = logins.findById(loginId);
        PartnerPortalSettings.Effective applied = settings.of(customerId);
        PartnerProductTiles.Catalog catalog = tiles.load();
        return new PartnerPortalViews.Overview(
                new PartnerPortalViews.PartnerInfo(customerId, customer.company(),
                        login == null ? null : login.displayName),
                new PartnerPortalViews.Costs(PartnerNumbers.pct(applied.auctionCommissionPct()),
                        PartnerNumbers.pct(applied.auctionTransportPct())),
                contact(),
                stockTiles(applied, catalog),
                reports.recentSales(customerId, catalog),
                reports.newest(customerId, OVERVIEW_REPORTS, catalog),
                PartnerPortalRules.today());
    }

    @Transactional
    public PartnerPortalViews.ReportPage reports(long customerId, Long before, Integer limit) {
        return reports.page(customerId, before, limit, tiles.load());
    }

    /** One melding of this partner (404 for another partner's). */
    @Transactional
    public PartnerPortalViews.PartnerReport report(long customerId, long reportId) {
        PartnerSaleReportEntity report = reportRows.findById(reportId);
        if (report == null || report.customerId == null || report.customerId != customerId) {
            throw new NotFoundException("Melding", reportId);
        }
        return reports.view(reports.load(List.of(report)).getFirst(), tiles.load());
    }

    /** Round 2: the containers' half of the photo guard (the same predicate as the container list). */
    @jakarta.inject.Inject
    PartnerContainerReader containerReader;

    /**
     * The sales photo of a product in this partner's current stock, in its meldingen or on a
     * container the partner may see (round 2); 404 for any other product and for a product
     * without a photo.
     */
    @Transactional
    public PhotoAnswer photo(long customerId, long productId, String size) {
        String profile = size == null || size.isBlank() ? "small" : size.strip();
        if (!"small".equals(profile) && !"medium".equals(profile)) throw new BadRequestException("Onbekende fotoversie");
        /* One product, one level and one count: a grid of tiles asks this once per tile. */
        Product product = productRepository.findById(productId).orElseThrow(() -> new NotFoundException("Foto", productId));
        if (!inStock(customerId, product) && !lines.productReported(customerId, productId)
                && !containerReader.productInVisibleContainer(productId)) {
            throw new NotFoundException("Foto", productId);
        }
        SalesPhotoService.Choice choice = SalesPhotoService.choose(product)
                .orElseThrow(() -> new NotFoundException("Foto", productId));
        Photo photo = choice.photo();
        String etag = "\"" + choice.version() + "-" + profile + "\"";
        return new PhotoAnswer(etag, () -> photos.render(photo, profile));
    }

    /** A stock tile shows an active product with a quantity above 0 at the partner's location. */
    private boolean inStock(long customerId, Product product) {
        if (!product.active()) return false;
        PartnerPortalSettings.Effective applied = settings.of(customerId);
        return stock.quantityAt(product.id(), applied.location().id()) > 0;
    }

    private List<PartnerPortalViews.StockTile> stockTiles(PartnerPortalSettings.Effective applied,
                                                          PartnerProductTiles.Catalog catalog) {
        Map<Long, Integer> quantities = stock.quantitiesAt(applied.location().id());
        List<PartnerPortalViews.ProductTile> shown = new ArrayList<>();
        quantities.forEach((productId, quantity) -> {
            Product product = catalog.product(productId).orElse(null);
            if (product != null && product.active() && quantity != null && quantity > 0) {
                shown.add(catalog.tile(productId));
            }
        });
        shown.sort(catalog.order());
        return shown.stream().map(tile -> PartnerPortalViews.StockTile.of(tile, quantities.get(tile.productId())))
                .toList();
    }

    /** The first staff account with a phone number; its name without a number when nobody has one. */
    private PartnerPortalViews.Contact contact() {
        List<StaffContactService.Contact> all = contacts.list();
        for (StaffContactService.Contact contact : all) {
            if (contact.phone() != null && !contact.phone().isBlank()) {
                return new PartnerPortalViews.Contact(contact.displayName(), contact.phone());
            }
        }
        return all.isEmpty() ? new PartnerPortalViews.Contact("Enrosed", null)
                : new PartnerPortalViews.Contact(all.getFirst().displayName(), null);
    }
}
