package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Barcodes;
import be.enrosed.catalog.domain.Carton;
import be.enrosed.catalog.domain.Dimensions;
import be.enrosed.catalog.domain.Product;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SalesOrderLogisticsValidationTest {

    private SalesRepositories.Orders orders;
    private SalesOrderService service;
    private ProductService products;
    private Product product;

    private final be.enrosed.shipping.application.CarrierRepository carriers =
            mock(be.enrosed.shipping.application.CarrierRepository.class);

    @BeforeEach
    void setUp() {
        orders = mock(SalesRepositories.Orders.class);
        products = mock(ProductService.class);
        CountryService countries = mock(CountryService.class);
        DiscountTierService tiers = mock(DiscountTierService.class);
        SalesPricingCalculator pricing = mock(SalesPricingCalculator.class);
        CustomerService customers = mock(CustomerService.class);
        VatCalculator vat = mock(VatCalculator.class);
        SalesRepositories.Events events = mock(SalesRepositories.Events.class);
        SalesRepositories.Revisions revisions = mock(SalesRepositories.Revisions.class);

        SalesSettings settings = new SalesSettings();
        settings.defaultMarkupPct = decimal("45");
        settings.palletLengthCm = decimal("120");
        settings.palletWidthCm = decimal("80");
        settings.palletBaseHeightCm = decimal("14.4");
        settings.palletMaxHeightCm = decimal("260");
        settings.palletMaxWeightKg = decimal("700");

        product = product();
        when(products.list()).thenReturn(List.of(product));
        when(countries.find("BE")).thenReturn(country());
        when(customers.get(1L)).thenReturn(customer());
        when(orders.save(any(SalesOrder.class))).thenAnswer(call -> call.getArgument(0));

        service = new SalesOrderService(orders, products, countries, tiers,
                pricing, new PalletCalculator(), settings, customers, vat, events, revisions,
                carriers, mock(be.enrosed.push.WebPushNotifier.class));
    }

    @Test
    void draftCanSelectLoosePerCbmBeforeTypingRateAndPreservesPalletLayout() {
        SalesOrder current = order(LoadMode.PALLETS, FreightPricingStrategy.COUNTRY_PALLET,
                decimal("220"), null, null,
                List.of(new OrderPallet(null, "Bewaren", "Europallet", 100,
                        List.of(new OrderPallet.Item(1L, 2)))));
        when(orders.findById(1L)).thenReturn(Optional.of(current));

        SalesOrder changes = order(LoadMode.LOOSE_CARTONS, FreightPricingStrategy.PER_CBM,
                null, null, null, current.pallets());
        SalesOrder saved = service.update(1L, changes);

        assertEquals(LoadMode.LOOSE_CARTONS, saved.loadMode());
        assertNull(saved.maxPalletHeightCm(), "null resets the per-order height override");
        assertNull(saved.freightRatePerCbmEur(), "draft autosave accepts the next field still empty");
        assertEquals(1, saved.pallets().size(), "hidden manual layout is preserved for switching back");
    }

    @Test
    void sendRejectsManualLayoutWithUnassignedCartons() {
        SalesOrder underAssigned = order(LoadMode.PALLETS,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                List.of(new OrderPallet(null, "Pallet 1", "Europallet", 100,
                        List.of(new OrderPallet.Item(1L, 1)))));

        BusinessRuleException failure = assertThrows(BusinessRuleException.class,
                () -> service.validateForSend(underAssigned));

        assertEquals("Verdeel alle 2 dozen van Testproduct over de pallets", failure.getMessage());
    }

    @Test
    void sendRejectsEmptyManualPallet() {
        SalesOrder empty = order(LoadMode.PALLETS,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                List.of(new OrderPallet(null, "Leeg", "Europallet", 100, List.of())));

        BusinessRuleException failure = assertThrows(BusinessRuleException.class,
                () -> service.validateForSend(empty));

        assertEquals("Verwijder lege pallets of zet er minstens één doos op", failure.getMessage());
    }

    @Test
    void sentLegacyPendingQuoteCanReceiveFixedFreightWithoutReopeningFrozenLayout() {
        when(products.list()).thenReturn(List.of(product(Carton.empty())));
        SalesOrder legacy = withWorkflow(
                order(LoadMode.PALLETS, FreightPricingStrategy.COUNTRY_PALLET,
                        null, null, null,
                        List.of(new OrderPallet(null, "Oude lege pallet", "Europallet", null,
                                List.of()))),
                QuoteStatus.VERZONDEN, FreightState.TE_BEPALEN);
        when(orders.findById(1L)).thenReturn(Optional.of(legacy));

        SalesOrder saved = service.updateFreight(1L, FreightState.BEREKEND,
                decimal("145"), FreightPricingStrategy.FIXED, null);

        assertEquals(QuoteStatus.VERZONDEN, saved.status());
        assertEquals(FreightState.AANGEVULD, saved.freight());
        assertEquals(FreightPricingStrategy.FIXED, saved.freightPricingStrategy());
        assertEquals(decimal("145"), saved.manualFreightEur());
        assertEquals(legacy.lines(), saved.lines());
        assertEquals(legacy.pallets(), saved.pallets());
    }

    private static SalesOrder order(LoadMode loadMode, FreightPricingStrategy strategy,
                                    BigDecimal maxHeight, BigDecimal fixed, BigDecimal perCbm,
                                    List<OrderPallet> pallets) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(1L, "Q-VALIDATE", 1L, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, "", MarkupMode.PRODUCT, decimal("45"),
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, fixed,
                loadMode, PalletProfile.EURO_120X80, maxHeight, strategy, perCbm,
                List.of(new SalesOrderLine(null, 1L, 20, null, null, null)), pallets);
    }

    private static SalesOrder withWorkflow(SalesOrder source, QuoteStatus status,
                                           FreightState freight) {
        return new SalesOrder(source.id(), source.number(), source.customerId(), source.countryCode(),
                source.orderDate(), source.validUntil(), status, source.incoterm(),
                source.paymentTerms(), source.notes(), source.markupMode(), source.orderMarkupPct(),
                source.extraDiscountPct(), source.extraDiscountLabel(), source.portalToken(),
                source.sentAt(), source.viewedAt(), source.viewCount(), source.decidedAt(),
                source.signedByName(), source.customerMessage(), source.internalNotes(),
                source.deliveryTerms(), freight, source.manualFreightEur(), source.loadMode(),
                source.palletProfile(), source.maxPalletHeightCm(), source.freightPricingStrategy(),
                source.freightRatePerCbmEur(), source.lines(), source.pallets());
    }

    private static Product product() {
        return product(new Carton(
                new Dimensions(decimal("40"), decimal("40"), decimal("20")),
                10, decimal("5")));
    }

    private static Product product(Carton carton) {
        return new Product(1L, "SKU-1", "Testproduct", Dimensions.empty(), null, null,
                1L, 1L, true, Barcodes.none(), null, carton,
                BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                decimal("1"), "test", decimal("45"), null, 100,
                List.of(), List.of());
    }

    private static Country country() {
        return new Country("BE", "België", BigDecimal.ZERO, decimal("90"),
                decimal("250"), decimal("35"), decimal("21"), 1, true);
    }

    private static Customer customer() {
        return new Customer(1L, "Klant", null, "test@example.com", null, null,
                "BE", null, null, null, null, "DAP", null, null, LocalDate.now());
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
