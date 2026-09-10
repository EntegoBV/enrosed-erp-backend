package be.enrosed.sales.application;

import be.enrosed.catalog.adapter.out.persistence.ProductEntity;
import be.enrosed.sales.adapter.in.rest.SalesOrderResource;
import be.enrosed.sales.adapter.out.persistence.PartnerInvoiceDeclarationEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.CustomerEntity;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.PartnerInvoiceDeclarations.Declaration;
import be.enrosed.sales.application.PartnerInvoiceDeclarations.Mode;
import be.enrosed.sales.application.port.out.QuoteDocumentRenderer;
import be.enrosed.sales.application.port.out.QuoteMailer;
import be.enrosed.sales.application.port.out.SalesPdfOptions;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderLineEntity;
import be.enrosed.sourcing.domain.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class PartnerInvoiceDeclarationsTest {
    @Inject PartnerInvoiceDeclarations declarations;
    @Inject SalesOrderService sales;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesOrderResource resource;
    @Inject CustomerService customers;
    @Inject SupplierService suppliers;
    @Inject PurchaseOrderService purchases;
    @Inject QuoteService quotes;
    @Inject QuoteDocumentRenderer renderer;
    @Inject ObjectMapper json;
    @Inject EntityManager entities;
    @InjectMock QuoteMailer mailer; // All mail in these tests is captured in memory.

    @Test @TestTransaction
    void resourceRoundTripPersistsOnlyTheExplicitWordingAndDefaultRemovesIt() throws Exception {
        var created = partnerInvoice(SalesPurpose.PARTNER_ADVANCE);
        entities.flush(); entities.clear();
        var order = sales.get(created.id());
        var before = sales.price(order);
        assertEquals(Declaration.defaults(), resource.invoiceDeclaration(order.id()));
        assertNull(entities.find(PartnerInvoiceDeclarationEntity.class, order.id()), "GET has no write side effect");
        var selected = resource.saveInvoiceDeclaration(order.id(), new Declaration(Mode.CUSTOMS_REPRESENTATIVE,
                "  DOS-2026-23  ", 1));
        assertEquals(customsDeclaration(), selected);
        var wire = json.readTree(json.writeValueAsString(selected));
        assertEquals("CUSTOMS_REPRESENTATIVE", wire.path("mode").asText());
        assertFalse(wire.has("logisticsProvider"));
        assertEquals(3, wire.size());
        assertEquals("DOS-2026-23", wire.path("reference").asText());
        assertEquals(1, wire.path("textVersion").asInt());
        entities.clear();
        assertEquals(selected, resource.invoiceDeclaration(order.id()));
        assertEquals(order, sales.get(order.id()), "identity, status, lines, notes and amounts remain untouched");
        assertEquals(before, sales.price(sales.get(order.id())));
        declarations.save(order.id(), selected);
        assertEquals(1L, declarationAuditCount(order.id()), "an identical retry adds no duplicate audit event");
        assertEquals(Declaration.defaults(), declarations.save(order.id(), Declaration.defaults()));
        entities.clear();
        assertNull(entities.find(PartnerInvoiceDeclarationEntity.class, order.id()));
        assertEquals(Declaration.defaults(), declarations.get(order.id()));
        assertEquals(2L, declarationAuditCount(order.id()));
    }

    @Test @TestTransaction
    void onlyCompatiblePartnerDraftsAndSupportedTextCanBeSelected() {
        var advance = partnerInvoice(SalesPurpose.PARTNER_ADVANCE);
        var settlement = partnerInvoice(SalesPurpose.PARTNER_SETTLEMENT);
        var plain = sales.create(advance.customerId(), "NL", "DAP", DocumentType.FACTUUR);
        var quote = sales.create(advance.customerId(), "NL", "DAP");
        for (var ordinary : List.of(plain, quote)) {
            assertEquals(Declaration.defaults(), declarations.get(ordinary.id()));
            assertThrows(BusinessRuleException.class, () -> declarations.save(ordinary.id(), customsDeclaration()));
        }
        assertThrows(BusinessRuleException.class, () -> declarations.save(settlement.id(), customsDeclaration()));
        assertThrows(BusinessRuleException.class, () -> declarations.save(advance.id(), reverseDeclaration()));
        assertThrows(BusinessRuleException.class, () -> declarations.save(advance.id(), new Declaration(null, null, 1)));
        assertThrows(BusinessRuleException.class, () -> declarations.save(advance.id(), new Declaration(Mode.CUSTOMS_REPRESENTATIVE, null, 2)));
        assertThrows(BusinessRuleException.class, () -> declarations.save(advance.id(), new Declaration(Mode.CUSTOMS_REPRESENTATIVE, "x".repeat(161), 1)));
        assertThrows(BusinessRuleException.class, () -> declarations.save(advance.id(), new Declaration(Mode.CUSTOMS_REPRESENTATIVE, "line\nnext", 1)));
        assertEquals(Declaration.defaults(), declarations.get(advance.id()));
    }

    @Test @TestTransaction
    void theDatabaseCascadesTheSidecarWhenAnUnusedInvoiceIsDeleted() {
        var order = partnerInvoice(SalesPurpose.PARTNER_ADVANCE);
        declarations.save(order.id(), customsDeclaration());
        entities.clear();
        sales.delete(order.id());
        entities.flush(); entities.clear();
        assertNull(entities.find(PartnerInvoiceDeclarationEntity.class, order.id()));
        assertThrows(be.enrosed.shared.NotFoundException.class, () -> declarations.get(order.id()));
    }

    @Test @TestTransaction
    void aCachedConceptCannotAllowEditsAfterConcurrentIssuance() {
        var order = partnerInvoice(SalesPurpose.PARTNER_ADVANCE);
        declarations.save(order.id(), customsDeclaration());
        var cached = entities.find(SalesOrderEntity.class, order.id());
        assertEquals(QuoteStatus.CONCEPT, cached.status);
        entities.createNativeQuery("update sales_order set status='UITGEREIKT' where id=:id")
                .setParameter("id", order.id()).executeUpdate();
        assertEquals(QuoteStatus.CONCEPT, cached.status, "simulate a stale read before waiting for the mutation lock");
        assertThrows(BusinessRuleException.class, () -> declarations.save(order.id(), Declaration.defaults()));
        assertEquals(QuoteStatus.UITGEREIKT, sales.get(order.id()).status());
        assertEquals(customsDeclaration(), declarations.get(order.id()));
        assertNull(sales.get(order.id()).sentAt());
    }

    @Test @TestTransaction
    void bothSelectionsRequireTheActualDutchRegimeAndNeverChangeAmounts() {
        for (var purpose : List.of(SalesPurpose.PARTNER_ADVANCE, SalesPurpose.PARTNER_SETTLEMENT)) {
            var created = partnerInvoice(purpose);
            entities.flush(); entities.clear();
            var order = sales.get(created.id());
            var selection = purpose == SalesPurpose.PARTNER_ADVANCE ? customsDeclaration() : reverseDeclaration();
            var before = sales.price(order);
            assertEquals(VatTreatment.VERLEGD_FISCAAL_VERTEGENWOORDIGER, before.totals().vatTreatment());
            declarations.save(order.id(), selection);
            assertEquals(before, sales.price(sales.get(order.id())));
            assertEquals(order, sales.get(order.id()));
            entities.find(CustomerEntity.class, order.customerId()).fiscalRepresentative = false;
            entities.flush(); entities.clear();
            assertEquals(VatTreatment.INTRACOMMUNAUTAIR, sales.price(order).totals().vatTreatment());
            assertThrows(BusinessRuleException.class, () -> declarations.save(order.id(), selection), "0% alone is insufficient");
            assertThrows(BusinessRuleException.class, () -> quotes.document(order.id(), Language.NL, SalesPdfOptions.defaults()));
            assertThrows(BusinessRuleException.class, () -> sales.issueInvoice(order.id()));
            assertThrows(BusinessRuleException.class, () -> quotes.send(order.id(), null));
            assertEquals(QuoteStatus.CONCEPT, sales.get(order.id()).status());
            assertNull(sales.get(order.id()).sentAt());
        }
        verifyNoInteractions(mailer);
    }

    @Test @TestTransaction
    void bothSelectionsRejectADifferentDestinationOrNonDutchVatNumber() {
        for (var purpose : List.of(SalesPurpose.PARTNER_ADVANCE, SalesPurpose.PARTNER_SETTLEMENT)) {
            var order = partnerInvoice(purpose);
            var selection = purpose == SalesPurpose.PARTNER_ADVANCE ? customsDeclaration() : reverseDeclaration();
            var buyer = entities.find(CustomerEntity.class, order.customerId());
            buyer.vatNumber = "BE0123456789";
            entities.flush(); entities.clear();
            assertThrows(BusinessRuleException.class, () -> declarations.save(order.id(), selection));
            entities.find(CustomerEntity.class, order.customerId()).vatNumber = "NL858617262B02";
            entities.find(SalesOrderEntity.class, order.id()).countryCode = "BE";
            entities.flush(); entities.clear();
            assertThrows(BusinessRuleException.class, () -> declarations.save(order.id(), selection));
            assertEquals(Declaration.defaults(), declarations.get(order.id()));
        }
    }

    @Test @TestTransaction
    void bothSelectionsRequireEveryBuyerAddressField() {
        for (var purpose : List.of(SalesPurpose.PARTNER_ADVANCE, SalesPurpose.PARTNER_SETTLEMENT)) {
            var order = partnerInvoice(purpose);
            var selection = purpose == SalesPurpose.PARTNER_ADVANCE ? customsDeclaration() : reverseDeclaration();
            for (String field : List.of("street", "postcode", "city", "country")) {
                var buyer = entities.find(CustomerEntity.class, order.customerId());
                buyer.address = "street".equals(field) ? null : "Kade 1";
                buyer.postalCode = "postcode".equals(field) ? " " : "5911 AB";
                buyer.city = "city".equals(field) ? null : "Venlo";
                buyer.countryCode = "country".equals(field) ? " " : "NL";
                entities.flush(); entities.clear();
                var error = assertThrows(BusinessRuleException.class, () -> declarations.save(order.id(), selection), field);
                assertTrue(error.getMessage().contains("volledige klantadres"), error.getMessage());
                assertEquals(Declaration.defaults(), declarations.get(order.id()));
            }
        }
    }

    @Test @TestTransaction
    void removingBuyerAddressAfterSelectionBlocksPdfIssuanceAndMail() {
        for (var purpose : List.of(SalesPurpose.PARTNER_ADVANCE, SalesPurpose.PARTNER_SETTLEMENT)) {
            var order = partnerInvoice(purpose);
            declarations.save(order.id(), purpose == SalesPurpose.PARTNER_ADVANCE ? customsDeclaration() : reverseDeclaration());
            entities.find(CustomerEntity.class, order.customerId()).address = null;
            entities.flush(); entities.clear();
            assertThrows(BusinessRuleException.class, () -> quotes.document(order.id(), Language.NL, SalesPdfOptions.defaults()));
            assertThrows(BusinessRuleException.class, () -> sales.issueInvoice(order.id()));
            assertThrows(BusinessRuleException.class, () -> quotes.send(order.id(), null));
            assertEquals(QuoteStatus.CONCEPT, sales.get(order.id()).status());
            assertNull(sales.get(order.id()).sentAt());
        }
        verifyNoInteractions(mailer);
    }

    @Test @TestTransaction
    void thePersistedCustomsStatementAppearsInCompactDownloadsAndTheActualMailAttachment() throws Exception {
        var order = partnerInvoice(SalesPurpose.PARTNER_ADVANCE);
        var before = sales.price(order);
        declarations.save(order.id(), customsDeclaration());
        var hidden = quotes.document(order.id(), Language.NL, new SalesPdfOptions(false, false, false, false, false, false, false));
        preview("partner-customs-representative.pdf", hidden.content());
        String downloaded = pdfText(hidden.content());
        assertCustoms(downloaded);
        assertFalse(downloaded.toLowerCase(java.util.Locale.ROOT).contains("btw verlegd"), "explicit English wording replaces the translated legal text");
        assertEquals(QuoteStatus.CONCEPT, sales.get(order.id()).status(), "a preview never issues or sends");
        var sent = quotes.send(order.id(), "Testbericht");
        var attachment = ArgumentCaptor.forClass(QuoteDocumentRenderer.Document.class);
        verify(mailer).sendInvoice(any(SalesOrder.class), any(Customer.class), attachment.capture(), eq("Testbericht"), anyString());
        assertCustoms(pdfText(attachment.getValue().content()));
        assertEquals(before.totals(), sales.price(sent).totals());
        assertEquals(QuoteStatus.VERZONDEN, sent.status(), "only the explicit mocked send transitions the document");
        assertNotNull(sent.sentAt());
    }

    @Test @TestTransaction
    void bothDeclarationsStayExactlyEnglishAcrossAllEightInvoiceLanguages() throws Exception {
        var advance = partnerInvoice(SalesPurpose.PARTNER_ADVANCE);
        declarations.save(advance.id(), customsDeclaration());
        var settlement = partnerInvoice(SalesPurpose.PARTNER_SETTLEMENT);
        declarations.save(settlement.id(), reverseDeclaration());
        for (var order : List.of(advance, settlement)) {
            var priced = sales.price(order);
            var buyer = customers.get(order.customerId());
            for (Language language : Language.values()) {
                var presentation = declarations.presentation(order, priced, buyer, language);
                assertEquals(PartnerInvoiceDeclarations.REVERSE_CHARGE_TEXT_V1, presentation.legalMentionOverride());
                assertEquals(order.isPartnerAdvance(), presentation.replaceCustomsLine());
                var pdf = renderer.render(order, priced, buyer, null, language,
                        new SalesPdfOptions(false, false, false, false, false, false, false));
                String text = pdfText(pdf.content());
                assertTrue(text.contains("“REVERSE CHARGE”: VAT shifted to Dutch customer according to article 12.3 Dutch VAT-Law."), language + ": " + text);
                assertEquals(1, text.split("article 12.3", -1).length - 1);
                assertTrue(text.contains("Kade 1"));
                assertTrue(text.contains("5911 AB"));
                assertTrue(text.contains("Venlo"));
                assertTrue(text.contains("NL858617262B02"));
                if (order.isPartnerAdvance()) {
                    assertCustoms(text);
                } else {
                    assertTrue(text.contains("File reference: RESULTAAT-2026."));
                    assertFalse(text.contains("24/7 Customs"));
                    if (language == Language.NL) preview("partner-settlement-reverse-charge.pdf", pdf.content());
                }
                assertFalse(text.contains("ESTA"));
                assertFalse(text.contains("article 23"));
                assertFalse(text.contains("artikel 23"));
            }
        }
    }

    @Test @TestTransaction
    void defaultKeepsTheExistingMandatoryTaxMentionAndAddsNoCustomText() throws Exception {
        var order = partnerInvoice(SalesPurpose.PARTNER_SETTLEMENT);
        var priced = sales.price(order);
        for (Language language : Language.values()) {
            var pdf = quotes.document(order.id(), language, new SalesPdfOptions(false, false, false, false, false, false, false));
            var text = pdfText(pdf.content());
            assertTrue(text.contains(priced.totals().vatTreatment().legalMentionIn(language)), language + ": " + text);
            assertFalse(text.contains("File reference:"));
        }
        assertNull(entities.find(PartnerInvoiceDeclarationEntity.class, order.id()));
    }

    private SalesOrder partnerInvoice(SalesPurpose purpose) {
        var partner = customers.create(new Customer(null, "Declaration partner", "Finance", "invoice@example.test", null,
                "NL858617262B02", "NL", Language.NL, "Kade 1", "5911 AB", "Venlo", "DAP", null, null,
                LocalDate.now(), true, new BigDecimal("50"), new BigDecimal("100"), true, null));
        var supplier = suppliers.save(new Supplier(null, "Declaration supplier", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        var purchase = purchases.create(supplier.id(), new BigDecimal("0.14"), BigDecimal.ONE, BigDecimal.ZERO);
        purchases.setPartner(purchase.id(), new PurchaseOrderService.PartnerRequest(partner.id(), new BigDecimal("100"), new BigDecimal("50")));
        var product = new ProductEntity();
        product.sku = "DECLARATION-" + UUID.randomUUID(); product.name = "Preserved roses"; product.supplierId = supplier.id();
        product.piecesPerCarton = 12; product.cartonLengthCm = product.cartonWidthCm = product.cartonHeightCm = BigDecimal.TEN;
        product.cartonWeightKg = BigDecimal.ONE;
        entities.persist(product); entities.flush();
        var container = entities.find(PurchaseOrderEntity.class, purchase.id());
        container.originCosts = container.freightUsd = container.destinationCostsEur = container.extraRevenueEur = BigDecimal.ZERO;
        container.defaultDutyRatePct = BigDecimal.ZERO;
        var line = new PurchaseOrderLineEntity(); line.order = container; line.productId = product.id; line.quantity = 12;
        line.exwPrice = new BigDecimal("1000"); line.exwCurrency = Currency.EUR;
        container.lines.add(line); entities.persist(line); entities.flush(); entities.clear();
        if (purpose == SalesPurpose.PARTNER_ADVANCE)
            return sales.createFromPurchaseOrder(new SalesOrderService.FromPurchaseOrderRequest(purchase.id(), partner.id(), "COST", BigDecimal.ZERO,
                    true, new BigDecimal("50"), new BigDecimal("100"), false, List.of(), "PARTNER", null,
                    SalesPurpose.PARTNER_ADVANCE, SalesPaymentPlan.FULL, null));
        var draft = sales.create(partner.id(), "NL", "DAP", DocumentType.FACTUUR);
        // A persisted legacy-shaped draft isolates declaration behavior from advance/auction planning.
        var order = orders.save(draft.withExtraLines(List.of(new SalesExtraLine("Partnerbedrag", BigDecimal.ONE, new BigDecimal("1234.56"))))
                .withPartnerDeal(purchase.id(), new BigDecimal("50")).withPurpose(purpose, purchase.id(), SalesPaymentPlan.FULL));
        entities.flush(); entities.clear();
        return sales.get(order.id());
    }

    private long declarationAuditCount(long id) {
        return ((Number) entities.createNativeQuery("select count(*) from activity_log where entity_type='SALES_ORDER' and entity_id=:id and summary='Factuurvermelding bijgewerkt'")
                .setParameter("id", Long.toString(id)).getSingleResult()).longValue();
    }
    private static Declaration customsDeclaration() { return new Declaration(Mode.CUSTOMS_REPRESENTATIVE, "DOS-2026-23", 1); }
    private static Declaration reverseDeclaration() { return new Declaration(Mode.REVERSE_CHARGE, "RESULTAAT-2026", 1); }
    private static String pdfText(byte[] content) throws Exception {
        try (var pdf = Loader.loadPDF(content)) { return new PDFTextStripper().getText(pdf).replaceAll("\\s+", " "); }
    }
    private static void assertCustoms(String text) {
        String customs = "Custom cleared in The Netherlands by our Limited Fiscal Representative: 24/7 Customs BV with VAT-no: NL858617262B02";
        String reverse = "“REVERSE CHARGE”: VAT shifted to Dutch customer according to article 12.3 Dutch VAT-Law.";
        assertTrue(text.contains(customs), text);
        assertTrue(text.contains(reverse), text);
        assertTrue(text.contains("File reference: DOS-2026-23."), text);
        assertEquals(1, text.split("24/7 Customs", -1).length - 1);
        assertTrue(text.indexOf(customs) < text.indexOf(reverse));
        assertTrue(text.indexOf(reverse) < text.indexOf("File reference:"));
    }
    private static void preview(String filename, byte[] content) throws Exception {
        var directory = Path.of(System.getProperty("enrosed.pdf.preview-dir", "/private/tmp/pdfs"));
        Files.createDirectories(directory);
        Files.write(directory.resolve(filename), content);
    }
}
