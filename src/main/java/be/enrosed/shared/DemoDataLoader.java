package be.enrosed.shared;

import be.enrosed.catalog.application.CategoryService;
import be.enrosed.catalog.application.HsCodeService;
import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.*;
import be.enrosed.sales.application.CountryService;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.DiscountTierService;
import be.enrosed.sales.domain.Country;
import be.enrosed.sales.domain.Customer;
import be.enrosed.sales.domain.DiscountTier;
import be.enrosed.sales.domain.TierScope;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.*;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Seed data.
 *
 * The nine rose products come from the supplier list; the "Preserved rose
 * with stem" comes from the Excel cost calculation and serves as a control
 * point: purchase order PO-2026-002 must land exactly on EUR 22.7385 per set.
 *
 * CAUTION - to be reconciled with real figures:
 *  - EXW prices of P01..P10 derive from the old EUR list
 *  - carton weights are estimates; they help decide cartons per pallet
 *  - pallet freight and minimum order values per country are indicative
 *  - import duty percentages should be checked in the EU's TARIC database
 */
@ApplicationScoped
public class DemoDataLoader {

    private static final Logger LOG = Logger.getLogger(DemoDataLoader.class);
    private static final BigDecimal CNY_TO_USD = new BigDecimal("0.1385");
    private static final BigDecimal USD_TO_EUR = new BigDecimal("0.89");

    private final CategoryService categories;
    private final HsCodeService hsCodes;
    private final ProductService products;
    private final SupplierService suppliers;
    private final PurchaseOrderService purchaseOrders;
    private final CustomerService customers;
    private final CountryService countries;
    private final DiscountTierService tiers;

    /** Opt-in only: an empty production database must never silently recreate test business data. */
    @ConfigProperty(name = "enrosed.demo-data.enabled", defaultValue = "false")
    boolean enabled;

    public DemoDataLoader(CategoryService categories, HsCodeService hsCodes, ProductService products,
                          SupplierService suppliers, PurchaseOrderService purchaseOrders,
                          CustomerService customers, CountryService countries, DiscountTierService tiers) {
        this.categories = categories;
        this.hsCodes = hsCodes;
        this.products = products;
        this.suppliers = suppliers;
        this.purchaseOrders = purchaseOrders;
        this.customers = customers;
        this.countries = countries;
        this.tiers = tiers;
    }

    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.info("Demo-startdata is uitgeschakeld");
            return;
        }
        if (!products.list().isEmpty()) {
            LOG.info("Startdata staat er al");
            return;
        }
        LOG.info("Startdata laden");

        /* ---- categories: a fixed list instead of free text -------------- */
        Category preserved = categories.create(new Category(null, "PRESERVED", "Preserved", "Geconserveerde bloemen", 1));
        Category glass = categories.create(new Category(null, "GLASS", "Glas", "Glazen stolpen en vazen", 2));
        Category acrylic = categories.create(new Category(null, "ACRYLIC", "Acryl", "Acryl boxen", 3));
        Category heart = categories.create(
                new Category(null, "HEART", "Heart box", "Hartvormige geschenkdozen", 4));

        /* ---- douanetarieven -------------------------------------------- */
        hsCodes.save(new HsCode(null, "0603.90.00", "Gedroogde, gebleekte of geverfde bloemen", new BigDecimal("10")));
        hsCodes.save(new HsCode(null, "7013.99.00", "Glaswerk voor decoratie", new BigDecimal("11")));
        hsCodes.save(new HsCode(null, "3926.40.00", "Decoratieartikelen van kunststof", new BigDecimal("6.5")));
        hsCodes.save(new HsCode(null, "4819.20.00", "Vouwdozen van karton", BigDecimal.ZERO));

        /* ---- leveranciers ---------------------------------------------- */
        Supplier culinan = suppliers.save(new Supplier(null, "Culinan Preserved Flowers Co., Ltd",
                "CN", "Kunming", "Lily Chen", "lily@culinan-flowers.cn", "+86 871 6788 4420",
                Currency.USD, "FOB", "Shanghai", 40, "Factureert in USD."));
        Supplier yiwu = suppliers.save(new Supplier(null, "Yiwu Rosegift Trading Co., Ltd",
                "CN", "Yiwu", "Frank Wu", "frank@rosegift-yiwu.cn", "+86 579 8532 1180",
                Currency.CNY, "EXW", "Ningbo", 35, "Quoteert in RMB, EXW fabriek."));

        /* ---- products ---------------------------------------------------
           Dimensions come from the product names on the container overview.
           Those carry two ("11*11cm"), so the third is an assumption: for
           round and square articles length is taken equal to width.
           Measure before the real catalogue. ----------------------------- */
        Product p01 = product("Acryl box", dim("11", "11", "11"), "Rood", acrylic.id(), yiwu.id(),
                "5401234001002", "5401234011001", "3926.40.00",
                "24", "24", "24", 16, "6", "24.84", Currency.CNY, "0.6", "45");
        Product p03 = product("Glass flower", dim("12", "12", "25"), "Rood", glass.id(), yiwu.id(),
                "5401234001019", "5401234011018", "7013.99.00",
                "58.5", "40", "34", 6, "12", "61.13", Currency.CNY, "1.2", "45");
        Product p04 = product("Acrylic flower", dim("12", "12", "20"), "Rood", acrylic.id(), yiwu.id(),
                "5401234001026", null, "3926.40.00",
                "52", "52", "49", 18, "11", "61.68", Currency.CNY, "1.2", "45");
        Product p05 = product("Glass flower - 1 rose", dim("15", "15", "30"), "Rood", glass.id(), yiwu.id(),
                "5401234001033", "5401234011032", "7013.99.00",
                "62", "41", "39", 6, "13", "74.97", Currency.CNY, "1.5", "45");
        Product p06 = product("Glass flower - 3 roses", dim("15", "15", "30"), "Rood", glass.id(), yiwu.id(),
                "5401234001040", "5401234011049", "7013.99.00",
                "62", "41", "39", 6, "14", "113.10", Currency.CNY, "1.5", "45");
        Product p07 = product("Heart box", dim("20", "20", "10"), "Rood", heart.id(), yiwu.id(),
                "5401234001057", "5401234011056", "3926.40.00",
                "100", "40", "40", 12, "15", "126.09", Currency.CNY, "2", "42");
        Product p08 = product("Heart box", dim("28", "28", "12"), "Rood", heart.id(), yiwu.id(),
                null, "5401234011063", "3926.40.00",
                "100", "40", "40", 10, "16", "125.12", Currency.CNY, "2", "42");
        Product p09 = product("Glass dome", dim("10", "10", "8"), "Gemengd", glass.id(), yiwu.id(),
                "5401234001071", "5401234011070", "7013.99.00",
                "39", "29", "39", 24, "9", "13.62", Currency.CNY, "0.4", "50");
        Product p10 = product("Glass dome", dim("5.5", "5.5", "6"), "Gemengd", glass.id(), yiwu.id(),
                null, null, "7013.99.00",
                "36", "39", "25", 40, "8", "6.82", Currency.CNY, "0.25", "55");

        /* The product from the Excel cost calculation. */
        Product p11 = product("Preserved rose with stem", dim("31.5", "23.3", "36.5"), "Rood",
                preserved.id(), culinan.id(),
                "6153402529533", "6153432789709", "0603.90.00",
                "68", "50", "40", 4, "10", "19.2", Currency.USD, "0.5", "35");

        /* Photos from the supplier's container overview. */
        attachPhoto(p01, "P01.jpg");
        attachPhoto(p03, "P03.jpg");
        attachPhoto(p04, "P04.jpg");
        attachPhoto(p05, "P05.jpg");
        attachPhoto(p06, "P06.jpg");
        attachPhoto(p07, "P07.jpg");
        attachPhoto(p08, "P08.jpg");
        attachPhoto(p09, "P09.jpg");
        attachPhoto(p10, "P10.jpg");

        /* ---- inkooporders ---------------------------------------------- */
        /* Yiwu delivers EXW: we pay factory -> port of Ningbo. */
        purchase(yiwu.id(), "4200", "850", Currency.USD, "1480", "0",
                List.of(line(p01.id(), 512), line(p03.id(), 1902), line(p04.id(), 306),
                        line(p05.id(), 150), line(p06.id(), 348), line(p07.id(), 504),
                        line(p08.id(), 500), line(p09.id(), 3984), line(p10.id(), 10000)),
                "Referentiecontainer uit de leverancierslijst.");

        /* Culinan delivers FOB: origin is already in their price. This is the Excel. */
        purchase(culinan.id(), "3717", "0", Currency.USD, "1155", "2000",
                List.of(line(p11.id(), 1968)),
                "Reproduceert de Excel: EUR 22,7385 per set.");

        purchaseOrders.list().forEach(order -> purchaseOrders.applyToProducts(order.id()));

        /* ---- landen ----------------------------------------------------- */
        country("BE", "Belgie", "1500", "55", "120", "25", "21", 1);
        country("NL", "Nederland", "1500", "65", "150", "25", "21", 1);
        country("DE", "Duitsland", "2000", "80", "200", "35", "19", 2);
        country("FR", "Frankrijk", "2000", "85", "220", "35", "20", 2);
        country("ES", "Spanje", "3000", "130", "320", "45", "21", 4);
        country("IT", "Italie", "3000", "135", "330", "45", "22", 4);
        country("PL", "Polen", "2500", "115", "280", "40", "23", 3);
        /* No longer an EU member since Brexit: deliveries there are exports. */
        country("GB", "Verenigd Koninkrijk", "3500", "130", "350", "95", "20", 4, false);
        country("CH", "Zwitserland", "4000", "150", "380", "120", "8.1", 3, false);

        /* ---- staffels --------------------------------------------------- */
        tiers.replace(TierScope.LINE, List.of(
                tier(TierScope.LINE, 0, "0"), tier(TierScope.LINE, 250, "2"),
                tier(TierScope.LINE, 500, "4"), tier(TierScope.LINE, 1000, "6"),
                tier(TierScope.LINE, 2500, "8"), tier(TierScope.LINE, 5000, "10")));
        tiers.replace(TierScope.ORDER, List.of(
                tier(TierScope.ORDER, 0, "0"), tier(TierScope.ORDER, 2000, "1"),
                tier(TierScope.ORDER, 5000, "2"), tier(TierScope.ORDER, 10000, "3"),
                tier(TierScope.ORDER, 20000, "5")));

        /* ---- klanten ---------------------------------------------------- */
        customers.create(new Customer(null, "Bloom & Co B.V.", "Sanne de Vries",
                "inkoop@bloomco.nl", "+31 10 214 88 30", "NL812345678B01", "NL", Language.NL,
                "Havenstraat 12", "3011 AB", "Rotterdam", "DAP",
                "50% voorschot / 50% bij levering", "Grote afnemer glass domes.", LocalDate.now()));
        customers.create(new Customer(null, "Fleurs de Lille SARL", "Camille Dubois",
                "camille@fleursdelille.fr", "+33 3 20 55 18 42", "FR40123456789", "FR", Language.FR,
                "Rue Nationale 88", "59800", "Lille", "DAP", "30 dagen", "", LocalDate.now()));

        LOG.infof("Startdata geladen: %d producten, %d klanten", products.list().size(), customers.list().size());
    }

    /* ------------------------------------------------------------ helpers */

    private Dimensions dim(String length, String width, String height) {
        return new Dimensions(new BigDecimal(length), new BigDecimal(width), new BigDecimal(height));
    }

    /**
     * Attaches the bundled photo to a product.
     *
     * The files live in src/main/resources/seed-images and come from the
     * supplier's container overview. When one is missing the product simply
     * goes on without a photo - that must not become a startup failure.
     */
    private void attachPhoto(Product product, String imageName) {
        String resource = "/seed-images/" + imageName;
        try (InputStream in = DemoDataLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warnf("Geen startfoto %s voor %s", resource, product.sku());
                return;
            }
            products.addPhoto(product.id(), imageName, in);
        } catch (Exception e) {
            LOG.warnf("Startfoto %s kon niet geladen worden: %s", resource, e.getMessage());
        }
    }

    private Product product(String name, Dimensions size, String colour, Long categoryId, Long supplierId,
                            String inner, String outer, String hsCode,
                            String cartonL, String cartonW, String cartonH, int perCarton, String weight,
                            String exw, Currency currency, String extraUnit, String markup) {
        return products.create(new Product(null, null, name, size, colour, null,
                categoryId, supplierId, true,
                new Barcodes(inner, outer), hsCode,
                new Carton(dim(cartonL, cartonW, cartonH), perCarton, new BigDecimal(weight)),
                new BigDecimal(exw), currency, new BigDecimal(extraUnit),
                null, null, new BigDecimal(markup), null, 0, List.of(), List.of()));
    }

    private PurchaseOrderLine line(Long productId, int quantity) {
        return new PurchaseOrderLine(null, productId, quantity, null, null, null, quantity);
    }

    private void purchase(Long supplierId, String freightUsd, String originCosts, Currency originCurrency,
                          String destinationEur, String extraRevenue, List<PurchaseOrderLine> lines,
                          String notes) {
        PurchaseOrder created = purchaseOrders.create(supplierId, CNY_TO_USD, USD_TO_EUR, new BigDecimal("10"));
        /* Seed through the real lifecycle as well. Apart from keeping demo
           data honest, this gives the placement save a chance to assign line
           ids and snapshot ordered quantities before receipt books stock. */
        PurchaseOrder placed = purchaseOrders.update(created.id(), new PurchaseOrder(
                created.id(), created.number(), null, supplierId, created.orderDate(),
                PurchaseOrderStatus.BESTELD, ContainerType.FORTY_HQ,
                CNY_TO_USD, USD_TO_EUR, USD_TO_EUR,
                new BigDecimal(freightUsd), new BigDecimal(originCosts), originCurrency,
                new BigDecimal(destinationEur), new BigDecimal("10"), new BigDecimal(extraRevenue),
                Allocation.CBM, Allocation.CBM, Allocation.CBM, Allocation.PIECES,
                created.departurePort(), "Rotterdam", notes, lines)).order();
        purchaseOrders.update(placed.id(), new PurchaseOrder(
                placed.id(), placed.number(), placed.alias(), placed.supplierId(), placed.orderDate(),
                PurchaseOrderStatus.ONTVANGEN, placed.containerType(),
                placed.cnyToUsd(), placed.usdToEurGoods(), placed.usdToEurTransport(),
                placed.freightUsd(), placed.originCosts(), placed.originCurrency(),
                placed.destinationCostsEur(), placed.defaultDutyRatePct(), placed.extraRevenueEur(),
                placed.allocFreight(), placed.allocOrigin(), placed.allocDestination(), placed.allocExtra(),
                placed.departurePort(), placed.destinationPort(), placed.notes(), placed.lines()));
    }

    private void country(String code, String name, String minOrder, String perPallet,
                         String minFreight, String handling, String vat, int transit) {
        country(code, name, minOrder, perPallet, minFreight, handling, vat, transit, true);
    }

    private void country(String code, String name, String minOrder, String perPallet,
                         String minFreight, String handling, String vat, int transit,
                         boolean euMember) {
        countries.save(new Country(code, name, new BigDecimal(minOrder), new BigDecimal(perPallet),
                new BigDecimal(minFreight), new BigDecimal(handling), new BigDecimal(vat),
                transit, euMember));
    }

    private DiscountTier tier(TierScope scope, int minQuantity, String percent) {
        return new DiscountTier(null, scope, minQuantity, new BigDecimal(percent));
    }
}
