package be.enrosed.sales.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.*;
import be.enrosed.sales.adapter.in.rest.PublicQuoteDtos;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.shipping.application.CarrierRepository;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicQuoteServiceTest {
    private ProductService products;
    private CountryService countries;
    private CustomerService customers;
    private SalesOrderService salesOrders;
    private CarrierRepository carriers;
    private Event<WebsiteQuotePushNotifier.Ready> websiteQuoteReady;
    private PublicQuoteService service;
    private Product pricedProduct;
    private Product missingPriceProduct;

    @BeforeEach
    void setUp() {
        products = mock(ProductService.class);
        countries = mock(CountryService.class);
        customers = mock(CustomerService.class);
        salesOrders = mock(SalesOrderService.class);
        carriers = mock(CarrierRepository.class);
        websiteQuoteReady = mock(Event.class);
        DiscountTierService tiers = mock(DiscountTierService.class);
        when(tiers.list(any())).thenReturn(List.of());
        SalesSettings settings = new SalesSettings();
        settings.defaultMarkupPct = decimal("45");
        settings.palletLengthCm = decimal("120");
        settings.palletWidthCm = decimal("80");
        settings.palletBaseHeightCm = decimal("14.4");
        settings.palletMaxHeightCm = decimal("260");
        settings.palletMaxWeightKg = decimal("700");
        VatCalculator vat = new VatCalculator();
        vat.homeCountry = "BE";
        service = new PublicQuoteService(products, countries, customers, salesOrders,
                tiers, new SalesPricingCalculator(new PalletCalculator(), new DeliveryCalculator()),
                settings, vat, carriers, websiteQuoteReady);

        pricedProduct = product(1L, "ROSE-1", decimal("10"));
        missingPriceProduct = product(2L, "ROSE-2", BigDecimal.ZERO);
        when(products.websiteOrderableProducts()).thenReturn(
                List.of(pricedProduct, missingPriceProduct));
        when(countries.find("BE")).thenReturn(country());
        when(countries.list()).thenReturn(List.of(country()));
        when(carriers.findAll()).thenReturn(List.of());
    }

    @Test
    void configurationNeverPresentsZeroAsFree() {
        PublicQuoteDtos.ConfigurationResponse result = service.configuration("EN");

        assertEquals(decimal("10"), result.products().get(0).unitPriceNet());
        assertTrue(result.products().get(0).priceAvailable());
        assertEquals(12, result.products().get(0).piecesPerCarton());
        assertNull(result.products().get(1).unitPriceNet());
        assertFalse(result.products().get(1).priceAvailable());
    }

    @Test
    void previewUsesCartonsAndOnlyReturnsSourcedCountryFreight() {
        PublicQuoteDtos.EstimateResponse result = service.preview(preview(1L, 2, "DELIVERY"));

        assertEquals(24, result.lines().getFirst().quantityPieces());
        assertEquals(2, result.lines().getFirst().cartons());
        assertEquals(decimal("10.0000"), result.lines().getFirst().unitPriceNet());
        assertEquals(decimal("240.00"), result.totals().goodsNet());
        assertEquals("CALCULATED", result.shipping().status());
        assertEquals("COUNTRY_TARIFF", result.shipping().source());
        assertEquals(decimal("285.00"), result.shipping().totalNet());
        assertEquals(decimal("635.25"), result.totals().totalInclVat());
        assertEquals("ESTIMATE_NOT_BINDING", result.estimateStatus());
    }

    @Test
    void missingPriceRequiresReviewAndNullsCommercialTotals() {
        PublicQuoteDtos.EstimateResponse result = service.preview(preview(2L, 1, "PICKUP"));

        assertTrue(result.validation().canSubmit());
        assertTrue(result.validation().requiresReview());
        assertEquals(List.of("PRICE_TO_CONFIRM", "MINIMUM_NOT_MET"),
                result.validation().messageCodes());
        assertFalse(result.lines().getFirst().priceAvailable());
        assertNull(result.lines().getFirst().unitPriceNet());
        assertNull(result.totals().goodsNet());
        assertNull(result.totals().totalInclVat());
        assertEquals("PICKUP", result.shipping().status());
        assertEquals(decimal("0.00"), result.shipping().totalNet());
    }

    @Test
    void pickupWorksWithoutDestinationObject() {
        PublicQuoteDtos.EstimateResponse result = service.preview(
                new PublicQuoteDtos.PreviewRequest("EN", "PICKUP", null, null,
                        List.of(new PublicQuoteDtos.ItemRequest(1L, 1))));

        assertEquals("PICKUP", result.fulfillment());
        assertEquals("PICKUP", result.shipping().status());
        assertEquals(decimal("0.00"), result.shipping().totalNet());
        assertEquals(decimal("145.20"), result.totals().totalInclVat());
    }

    @Test
    void missingCartonDataIsNeverInventedAsOnePiecePerCarton() {
        Product noCarton = new Product(3L, "NO-CARTON", "No carton", Dimensions.empty(),
                null, null, 1L, 1L, true, Barcodes.none(), null,
                Carton.empty(),
                BigDecimal.ZERO, Currency.EUR, BigDecimal.ZERO, decimal("1"), "test",
                decimal("45"), decimal("10"), 0, List.of(), List.of());
        when(products.websiteOrderableProducts()).thenReturn(List.of(noCarton));

        PublicQuoteDtos.ConfigurationResponse config = service.configuration("EN");
        PublicQuoteDtos.EstimateResponse preview = service.preview(
                new PublicQuoteDtos.PreviewRequest("EN", "DELIVERY", null,
                        new PublicQuoteDtos.Destination("BE", "2400", "Mol", "Street 1"),
                        List.of(new PublicQuoteDtos.ItemRequest(3L, 4))));

        assertEquals(0, config.products().getFirst().piecesPerCarton());
        assertEquals(0, preview.lines().getFirst().quantityPieces());
        assertNull(preview.lines().getFirst().lineTotalNet());
        assertEquals("TO_CONFIRM", preview.shipping().status());
        assertTrue(preview.validation().messageCodes().contains("CARTON_DATA_TO_CONFIRM"));
    }

    @Test
    void knownCartonContentDrivesPreviewAndSavedQuantityWithoutLogisticsDimensions() {
        Product missingDimensions = new Product(3L, "NO-DIMENSIONS", "No dimensions",
                Dimensions.empty(), null, null, 1L, 1L, true, Barcodes.none(), null,
                new Carton(Dimensions.empty(), 12, decimal("5")),
                BigDecimal.ZERO, Currency.EUR, BigDecimal.ZERO, decimal("1"), "test",
                decimal("45"), decimal("10"), 0, List.of(), List.of());
        when(products.websiteOrderableProducts()).thenReturn(List.of(missingDimensions));

        PublicQuoteDtos.ConfigurationResponse config = service.configuration("EN");
        PublicQuoteDtos.EstimateResponse preview = service.preview(
                new PublicQuoteDtos.PreviewRequest("EN", "DELIVERY", null,
                        new PublicQuoteDtos.Destination("BE", "2400", "Mol", "Street 1"),
                        List.of(new PublicQuoteDtos.ItemRequest(3L, 3))));

        assertEquals(12, config.products().getFirst().piecesPerCarton());
        assertEquals(3, preview.lines().getFirst().cartons());
        assertEquals(36, preview.lines().getFirst().quantityPieces());
        assertEquals(decimal("360.00"), preview.totals().goodsNet());
        assertEquals("TO_CONFIRM", preview.shipping().status());
        assertTrue(preview.validation().messageCodes().contains("CARTON_DATA_TO_CONFIRM"));

        Customer savedCustomer = new Customer(9L, "Buyer BV", "Ana", "ana@example.com",
                null, null, "BE", Language.EN, "Street 1", "2400", "Mol",
                "DAP", null, null, LocalDate.now());
        when(customers.create(any())).thenReturn(savedCustomer);
        when(salesOrders.createWebsiteRequest(9L, "BE", "DAP"))
                .thenReturn(draft(45L, 9L, "ENR-2026-0045"));
        when(salesOrders.update(eq(45L), any())).thenAnswer(invocation -> invocation.getArgument(1));

        service.submit(new PublicQuoteDtos.SubmitRequest(
                "EN", "DELIVERY", null,
                new PublicQuoteDtos.Destination("BE", "2400", "Mol", "Street 1"),
                List.of(new PublicQuoteDtos.ItemRequest(3L, 3)),
                "BE", "Buyer BV", "Ana", "ana@example.com", null, null, true, ""));

        ArgumentCaptor<SalesOrder> saved = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrders).update(eq(45L), saved.capture());
        SalesOrder quote = saved.getValue();
        assertEquals(36, quote.lines().getFirst().quantity());
        assertEquals(decimal("10.0000"), quote.lines().getFirst().unitPriceEur());
        assertEquals(FreightState.TE_BEPALEN, quote.freight());
        assertFalse(quote.internalNotes().contains(
                SalesOrderService.WEBSITE_CARTON_UNRESOLVED_MARKER));
    }

    @Test
    void submissionCreatesExistingErpQuoteAndFreezesServerPrice() {
        Customer savedCustomer = new Customer(9L, "Buyer BV", "Ana", "ana@example.com",
                null, "BE0123456789", "BE", Language.EN, "Street 1", "2400", "Mol",
                "DAP", null, null, LocalDate.now());
        when(customers.create(any())).thenReturn(savedCustomer);
        SalesOrder draft = draft(41L, 9L, "ENR-2026-0041");
        when(salesOrders.createWebsiteRequest(9L, "BE", "DAP")).thenReturn(draft);
        when(salesOrders.update(eq(41L), any())).thenAnswer(invocation -> invocation.getArgument(1));

        PublicQuoteDtos.SubmitRequest request = new PublicQuoteDtos.SubmitRequest(
                "EN", "DELIVERY", "BE0123456789",
                new PublicQuoteDtos.Destination("BE", "2400", "Mol", "Street 1"),
                List.of(new PublicQuoteDtos.ItemRequest(1L, 2)),
                "BE",
                "Buyer BV", "Ana", "ana@example.com", null,
                "Need red", true, "");
        PublicQuoteDtos.SubmissionResponse response = service.submit(request);

        assertEquals("ENR-2026-0041", response.reference());
        assertEquals("REQUEST_RECEIVED_NOT_BINDING", response.bindingStatus());
        ArgumentCaptor<SalesOrder> saved = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrders).update(eq(41L), saved.capture());
        SalesOrder quote = saved.getValue();
        assertTrue(quote.internalNotes().startsWith(
                "[WEBSITE_AANVRAAG] ENR-2026-0041\n"));
        assertEquals("Need red", quote.notes());
        assertEquals(24, quote.lines().getFirst().quantity());
        assertEquals(decimal("10.0000"), quote.lines().getFirst().unitPriceEur());
        InOrder persistedBeforeNotification = inOrder(salesOrders, websiteQuoteReady);
        persistedBeforeNotification.verify(salesOrders).update(eq(41L), any());
        persistedBeforeNotification.verify(websiteQuoteReady).fire(
                new WebsiteQuotePushNotifier.Ready(41L, "ENR-2026-0041"));
        verify(salesOrders, never()).create(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void submissionPreservesRequestedCartonsWhenPieceCountIsUnknown() {
        Product noCarton = new Product(3L, "NO-CARTON", "No carton", Dimensions.empty(),
                null, null, 1L, 1L, true, Barcodes.none(), null,
                Carton.empty(),
                BigDecimal.ZERO, Currency.EUR, BigDecimal.ZERO, decimal("1"), "test",
                decimal("45"), decimal("10"), 0, List.of(), List.of());
        when(products.websiteOrderableProducts()).thenReturn(List.of(noCarton));
        Customer savedCustomer = new Customer(9L, "Buyer BV", "Ana", "ana@example.com",
                null, null, "BE", Language.EN, null, null, null,
                "EXW", null, null, LocalDate.now());
        when(customers.create(any())).thenReturn(savedCustomer);
        when(salesOrders.createWebsiteRequest(9L, "BE", "EXW"))
                .thenReturn(draft(42L, 9L, "ENR-2026-0042"));
        when(salesOrders.update(eq(42L), any())).thenAnswer(invocation -> invocation.getArgument(1));

        service.submit(new PublicQuoteDtos.SubmitRequest(
                "EN", "PICKUP", null, null,
                List.of(new PublicQuoteDtos.ItemRequest(3L, 4)),
                "BE",
                "Buyer BV", "Ana", "ana@example.com", null, null, true, ""));

        ArgumentCaptor<SalesOrder> saved = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrders).update(eq(42L), saved.capture());
        assertEquals(0, saved.getValue().lines().getFirst().quantity(),
                "unknown carton content must never be priced as four individual pieces");
        assertTrue(saved.getValue().internalNotes().contains(
                "[DOOSINHOUD_TE_BEPALEN] productId=3; sku=NO-CARTON; cartons=4; "
                        + "quantityPieces=TE_BEPALEN"));
    }

    @Test
    void pickupStoresTheBuyersCountryWithoutDefaultingTheCompanyToBelgium() {
        when(countries.find("NL")).thenReturn(country("NL"));
        when(customers.create(any())).thenAnswer(invocation ->
                identified(invocation.getArgument(0), 9L));
        when(salesOrders.createWebsiteRequest(9L, "BE", "EXW"))
                .thenReturn(draft(43L, 9L, "ENR-2026-0043"));
        when(salesOrders.update(eq(43L), any())).thenAnswer(invocation -> invocation.getArgument(1));

        service.submit(new PublicQuoteDtos.SubmitRequest(
                "EN", "PICKUP", "NL123456789B01", null,
                List.of(new PublicQuoteDtos.ItemRequest(1L, 1)),
                "NL", "Dutch Buyer BV", "Ana", "ana@example.com", null,
                null, true, ""));

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customers).create(saved.capture());
        assertEquals("NL", saved.getValue().countryCode());
        assertNull(saved.getValue().address());
        verify(salesOrders).createWebsiteRequest(9L, "BE", "EXW");
    }

    @Test
    void deliveryKeepsCompanyCountryDistinctFromTheShipToCountry() {
        when(countries.find("NL")).thenReturn(country("NL"));
        when(countries.find("FR")).thenReturn(country("FR"));
        when(customers.create(any())).thenAnswer(invocation ->
                identified(invocation.getArgument(0), 9L));
        when(salesOrders.createWebsiteRequest(9L, "FR", "DAP"))
                .thenReturn(draft(44L, 9L, "ENR-2026-0044"));
        when(salesOrders.update(eq(44L), any())).thenAnswer(invocation -> invocation.getArgument(1));

        service.submit(new PublicQuoteDtos.SubmitRequest(
                "EN", "DELIVERY", "NL123456789B01",
                new PublicQuoteDtos.Destination("FR", "75001", "Paris", "1 Rue de Test"),
                List.of(new PublicQuoteDtos.ItemRequest(1L, 1)),
                "NL", "Dutch Buyer BV", "Ana", "ana@example.com", null,
                null, true, ""));

        ArgumentCaptor<Customer> customer = ArgumentCaptor.forClass(Customer.class);
        ArgumentCaptor<SalesOrder> quote = ArgumentCaptor.forClass(SalesOrder.class);
        verify(customers).create(customer.capture());
        verify(salesOrders).update(eq(44L), quote.capture());
        assertEquals("NL", customer.getValue().countryCode());
        assertEquals("1 Rue de Test", customer.getValue().address());
        assertEquals("FR", quote.getValue().countryCode());
        verify(salesOrders).createWebsiteRequest(9L, "FR", "DAP");
    }

    @Test
    void rejectsPrivateProductAndMissingPrivacyConsent() {
        PublicQuoteValidationException privateProduct = assertThrows(
                PublicQuoteValidationException.class,
                () -> service.preview(preview(99L, 1, "DELIVERY")));
        assertEquals("NOT_ORDERABLE", privateProduct.fieldErrors().get("items[0].productId"));

        PublicQuoteDtos.SubmitRequest noConsent = new PublicQuoteDtos.SubmitRequest(
                "EN", "PICKUP", null,
                new PublicQuoteDtos.Destination("BE", null, null, null),
                List.of(new PublicQuoteDtos.ItemRequest(1L, 1)),
                "BE",
                "Buyer BV", "Ana", "ana@example.com", null, null, false, "");
        PublicQuoteValidationException consent = assertThrows(
                PublicQuoteValidationException.class, () -> service.submit(noConsent));
        assertEquals("REQUIRED", consent.fieldErrors().get("privacyAccepted"));
        verifyNoInteractions(customers, salesOrders);
    }

    @Test
    void rejectsMissingOrUnsupportedCompanyCountry() {
        PublicQuoteDtos.SubmitRequest missingCountry = new PublicQuoteDtos.SubmitRequest(
                "EN", "PICKUP", null, null,
                List.of(new PublicQuoteDtos.ItemRequest(1L, 1)),
                null, "Buyer BV", "Ana", "ana@example.com", null, null, true, "");
        PublicQuoteValidationException missing = assertThrows(
                PublicQuoteValidationException.class, () -> service.submit(missingCountry));
        assertEquals("REQUIRED", missing.fieldErrors().get("companyCountryCode"));

        PublicQuoteDtos.SubmitRequest unsupportedCountry = new PublicQuoteDtos.SubmitRequest(
                "EN", "PICKUP", null, null,
                List.of(new PublicQuoteDtos.ItemRequest(1L, 1)),
                "XX", "Buyer BV", "Ana", "ana@example.com", null, null, true, "");
        PublicQuoteValidationException unsupported = assertThrows(
                PublicQuoteValidationException.class, () -> service.submit(unsupportedCountry));
        assertEquals("UNSUPPORTED", unsupported.fieldErrors().get("companyCountryCode"));
        verifyNoInteractions(customers, salesOrders);
    }

    @Test
    void deliverySubmissionReportsEveryMissingAddressFieldEvenWithoutDestinationObject() {
        PublicQuoteDtos.SubmitRequest missingDestination = new PublicQuoteDtos.SubmitRequest(
                "EN", "DELIVERY", null, null,
                List.of(new PublicQuoteDtos.ItemRequest(1L, 1)),
                "BE", "Buyer BV", "Ana", "ana@example.com", null, null, true, "");

        PublicQuoteValidationException failure = assertThrows(
                PublicQuoteValidationException.class, () -> service.submit(missingDestination));

        assertEquals("REQUIRED", failure.fieldErrors().get("destination.address"));
        assertEquals("REQUIRED", failure.fieldErrors().get("destination.city"));
        assertEquals("UNSUPPORTED", failure.fieldErrors().get("destination.countryCode"));
        assertEquals("REQUIRED", failure.fieldErrors().get("destination.postalCode"));
        verifyNoInteractions(customers, salesOrders);
    }

    @Test
    void malformedItemsAndDestinationAreRejectedTogetherWithoutPersistence() {
        PublicQuoteDtos.PreviewRequest invalid = new PublicQuoteDtos.PreviewRequest(
                "XX", "COURIER", "1",
                new PublicQuoteDtos.Destination("XX", "p".repeat(25), "c".repeat(101),
                        "a".repeat(201)),
                List.of(
                        new PublicQuoteDtos.ItemRequest(1L, 0),
                        new PublicQuoteDtos.ItemRequest(1L, 1),
                        new PublicQuoteDtos.ItemRequest(99L, 10_001)));

        PublicQuoteValidationException failure = assertThrows(
                PublicQuoteValidationException.class, () -> service.preview(invalid));

        assertEquals("UNSUPPORTED", failure.fieldErrors().get("language"));
        assertEquals("UNSUPPORTED", failure.fieldErrors().get("fulfillment"));
        assertEquals("UNSUPPORTED", failure.fieldErrors().get("destination.countryCode"));
        assertEquals("TOO_LONG", failure.fieldErrors().get("destination.postalCode"));
        assertEquals("TOO_LONG", failure.fieldErrors().get("destination.city"));
        assertEquals("TOO_LONG", failure.fieldErrors().get("destination.address"));
        assertEquals("INVALID", failure.fieldErrors().get("vatNumber"));
        assertEquals("OUT_OF_RANGE", failure.fieldErrors().get("items[0].cartons"));
        assertEquals("DUPLICATE", failure.fieldErrors().get("items[1].productId"));
        assertEquals("NOT_ORDERABLE", failure.fieldErrors().get("items[2].productId"));
        verifyNoInteractions(customers, salesOrders);
    }

    @Test
    void honeypotAndInvalidContactNeverCreateCustomerOrQuote() {
        PublicQuoteDtos.SubmitRequest bot = new PublicQuoteDtos.SubmitRequest(
                "EN", "PICKUP", "1", null,
                List.of(new PublicQuoteDtos.ItemRequest(1L, 1)),
                "BE", "Buyer BV", "Ana", "not-an-email", "p".repeat(51),
                "n".repeat(2001), true, "https://spam.example");

        PublicQuoteValidationException failure = assertThrows(
                PublicQuoteValidationException.class, () -> service.submit(bot));

        assertEquals("INVALID", failure.fieldErrors().get("request"));
        assertEquals("INVALID", failure.fieldErrors().get("email"));
        assertEquals("INVALID", failure.fieldErrors().get("vatNumber"));
        assertEquals("TOO_LONG", failure.fieldErrors().get("phone"));
        assertEquals("TOO_LONG", failure.fieldErrors().get("notes"));
        verifyNoInteractions(customers, salesOrders);
    }

    private static PublicQuoteDtos.PreviewRequest preview(long productId, int cartons,
                                                          String fulfillment) {
        return new PublicQuoteDtos.PreviewRequest("EN", fulfillment, null,
                new PublicQuoteDtos.Destination("BE", "2400", "Mol", "Street 1"),
                List.of(new PublicQuoteDtos.ItemRequest(productId, cartons)));
    }

    private static Product product(long id, String sku, BigDecimal fixedPrice) {
        return new Product(id, sku, sku, Dimensions.empty(), null, null,
                1L, 1L, true, Barcodes.none(), null,
                new Carton(new Dimensions(decimal("40"), decimal("30"), decimal("20")),
                        12, decimal("5")),
                BigDecimal.ZERO, Currency.USD, BigDecimal.ZERO,
                fixedPrice.signum() > 0 ? decimal("1") : BigDecimal.ZERO,
                "test", decimal("45"), fixedPrice, 1000,
                List.of(), List.of());
    }

    private static Country country() {
        return country("BE");
    }

    private static Country country(String code) {
        return new Country(code, code, decimal("100"), decimal("90"),
                decimal("250"), decimal("35"), decimal("21"), 1, true);
    }

    private static Customer identified(Customer source, long id) {
        return new Customer(id, source.company(), source.contact(), source.email(), source.phone(),
                source.vatNumber(), source.countryCode(), source.language(), source.address(),
                source.postalCode(), source.city(), source.incoterm(), source.paymentTerms(),
                source.notes(), LocalDate.now());
    }

    private static SalesOrder draft(long id, long customerId, String number) {
        LocalDate today = LocalDate.now();
        return new SalesOrder(id, number, customerId, "BE", today, today.plusDays(30),
                QuoteStatus.CONCEPT, "DAP", null, null, MarkupMode.PRODUCT, decimal("45"),
                null, null, null, null, null, 0, null, null, null, null,
                DeliveryTermsState.VOLLEDIG, FreightState.BEREKEND, null,
                LoadMode.PALLETS, PalletProfile.EURO_120X80, null,
                FreightPricingStrategy.COUNTRY_PALLET, null, null, null,
                DocumentType.OFFERTE, null, null, null, null, List.of(), List.of());
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
