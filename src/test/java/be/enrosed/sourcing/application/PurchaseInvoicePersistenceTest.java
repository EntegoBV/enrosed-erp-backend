package be.enrosed.sourcing.application;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.sourcing.adapter.in.rest.SourcingResource;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.PurchaseInvoiceService.InvoiceRequest;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestSecurity(user = "fixture-admin", roles = "admin")
class PurchaseInvoicePersistenceTest {
    @Inject PurchaseOrderService purchases;
    @Inject PurchaseInvoiceService invoices;
    @Inject SupplierService suppliers;
    @Inject SourcingResource resource;
    @Inject EntityManager em;

    private static final LocalDate INVOICED = LocalDate.of(2026, 8, 20);

    /** Example (a): one commercial invoice for the whole container, the payment terms point at it. */
    @Test
    @TestTransaction
    void theCommercialInvoiceCarriesEveryPaymentOfTheSupplier() {
        PurchaseOrder order = container();
        PurchaseDocument file = file(order, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, "CI-2026-118.pdf");
        PurchaseDocument old = file(order, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, "invoice.pdf");
        PurchasePayment deposit = pay(order, "9314.41", "Aanbetaling 30%", Payee.SUPPLIER);
        PurchasePayment balance = pay(order, "21733.63", "Saldo 70%", Payee.SUPPLIER);

        var view = invoices.add(order.id(), request(Payee.SUPPLIER, "CI-2026-118", "31048.04", file.id()));

        assertEquals("CI-2026-118", view.number());
        assertEquals(Payee.SUPPLIER, view.payee());
        assertEquals("Leverancier", view.payeeLabel());
        assertEquals(INVOICED, view.invoiceDate());
        assertEquals(new BigDecimal("31048.04"), view.amount());
        assertEquals(Currency.EUR, view.currency());
        assertEquals(new BigDecimal("31048.04"), view.amountEur());
        assertEquals(file.id(), view.documentId());
        assertEquals("CI-2026-118.pdf", view.documentName());
        assertTrue(view.primary());
        assertFalse(view.coversInspection());
        assertEquals(List.of(deposit.id(), balance.id()), view.paymentIds());
        assertEquals(new BigDecimal("31048.04"), view.paidEur());
        assertTrue(view.actor() != null && view.recordedAt() != null);

        var page = resource.getPurchaseOrder(order.id());
        assertEquals(1, page.invoices().size());
        assertEquals(1, page.invoiceFiles().size(), "the older file without data is listed to fill in");
        assertEquals(old.id(), page.invoiceFiles().getFirst().documentId());
        assertEquals(Payee.SUPPLIER, page.invoiceFiles().getFirst().payee());
        assertEquals("invoice.pdf", page.invoiceFiles().getFirst().originalFilename());
    }

    @Test
    @TestTransaction
    void everyInvoiceRuleSpeaksDutch() {
        PurchaseOrder order = container();
        PurchaseOrder other = container();
        PurchaseDocument foreignFile = file(other, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, "elders.pdf");
        PurchaseDocument held = file(order, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, "CI-1.pdf");
        invoices.add(order.id(), request(Payee.SUPPLIER, "CI-1", "100", held.id()));

        refused(order, "Kies bij wie de factuur hoort", () -> new InvoiceRequest(null, "X", INVOICED, BigDecimal.TEN, null, null, null, null));
        refused(order, "Vul het factuurnummer in", () -> new InvoiceRequest(Payee.SUPPLIER, "  ", INVOICED, BigDecimal.TEN, null, null, null, null));
        refused(order, "Het factuurnummer mag maximaal 64 tekens bevatten", () -> new InvoiceRequest(Payee.SUPPLIER, "X".repeat(65), INVOICED, BigDecimal.TEN, null, null, null, null));
        refused(order, "Vul de factuurdatum in", () -> new InvoiceRequest(Payee.SUPPLIER, "X", null, BigDecimal.TEN, null, null, null, null));
        refused(order, "De factuurdatum kan niet in de toekomst liggen", () -> new InvoiceRequest(Payee.SUPPLIER, "X",
                LocalDate.now(PurchaseOrderService.BRUSSELS).plusDays(2), BigDecimal.TEN, null, null, null, null));
        refused(order, "Geef een factuurbedrag groter dan nul op", () -> new InvoiceRequest(Payee.SUPPLIER, "X", INVOICED, BigDecimal.ZERO, null, null, null, null));
        refused(order, "Een factuurbedrag heeft maximaal twee decimalen", () -> new InvoiceRequest(Payee.SUPPLIER, "X", INVOICED, new BigDecimal("1.005"), null, null, null, null));
        refused(order, "Voor een factuur in euro is het eurobedrag gelijk aan het bedrag", () -> new InvoiceRequest(Payee.SUPPLIER, "X", INVOICED, BigDecimal.TEN, Currency.EUR, new BigDecimal("9"), null, null));
        refused(order, "Geef een eurobedrag groter dan nul op", () -> new InvoiceRequest(Payee.SUPPLIER, "X", INVOICED, BigDecimal.TEN, Currency.USD, BigDecimal.ZERO, null, null));
        refused(order, "Dit bestand hoort niet bij deze inkooporder", () -> new InvoiceRequest(Payee.SUPPLIER, "X", INVOICED, BigDecimal.TEN, null, null, foreignFile.id(), null));
        refused(order, "Dit bestand hoort al bij factuur CI-1", () -> new InvoiceRequest(Payee.SUPPLIER, "X", INVOICED, BigDecimal.TEN, null, null, held.id(), null));
        refused(order, "Factuur ci-1 staat al bij deze container", () -> new InvoiceRequest(Payee.SUPPLIER, " ci-1 ", INVOICED, BigDecimal.TEN, null, null, null, null));
        refused(order, "De notitie mag maximaal 500 tekens bevatten", () -> new InvoiceRequest(Payee.SUPPLIER, "X", INVOICED, BigDecimal.TEN, null, null, null, "n".repeat(501)));
        /* The same number under another payee is another invoice. */
        assertEquals("CI-1", invoices.add(order.id(), request(Payee.LOGISTICS, "CI-1", "50", null)).number());
        for (int i = 3; i <= 20; i++) invoices.add(order.id(), request(Payee.OTHER, "K-" + i, "1", null));
        refused(order, "Per container horen hoogstens twintig facturen", () -> request(Payee.OTHER, "K-21", "1", null));
    }

    @Test
    @TestTransaction
    void aSecondInvoiceAddsUpAndNeverMovesTheExistingPayments() {
        PurchaseOrder order = container();
        PurchasePayment deposit = pay(order, "9314.41", "Aanbetaling 30%", Payee.SUPPLIER);
        var first = invoices.add(order.id(), request(Payee.SUPPLIER, "CI-2026-118", "31048.04", null));
        assertNull(purchases.payments(order.id()).getFirst().invoiceId(), "one invoice: automatic, nothing stored");

        var extra = invoices.add(order.id(), request(Payee.SUPPLIER, "CI-2026-140", "500", null));

        assertEquals(first.id(), purchases.payments(order.id()).getFirst().invoiceId(), "pinned on the first invoice");
        var views = invoices.list(order.id());
        assertEquals(List.of(deposit.id()), views.stream().filter(view -> view.id().equals(first.id())).findFirst().orElseThrow().paymentIds());
        assertEquals(List.of(), views.stream().filter(view -> view.id().equals(extra.id())).findFirst().orElseThrow().paymentIds());
        assertTrue(views.stream().filter(view -> view.id().equals(first.id())).findFirst().orElseThrow().primary());
        assertFalse(views.stream().filter(view -> view.id().equals(extra.id())).findFirst().orElseThrow().primary());
        /* A payment noted later, without a choice, belongs to neither: the payee has two invoices. */
        pay(order, "100", "Later", Payee.SUPPLIER);
        assertEquals(1, invoices.list(order.id()).stream().mapToInt(view -> view.paymentIds().size()).sum());
    }

    @Test
    @TestTransaction
    void deletingAnInvoiceKeepsItsFileAndPutsItsPaymentsBackOnAutomatic() {
        PurchaseOrder order = container();
        PurchaseDocument file = file(order, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, "CI-2026-118.pdf");
        pay(order, "9314.41", "Aanbetaling 30%", Payee.SUPPLIER);
        var first = invoices.add(order.id(), request(Payee.SUPPLIER, "CI-2026-118", "31048.04", file.id()));
        invoices.add(order.id(), request(Payee.SUPPLIER, "CI-2026-140", "500", null));
        assertEquals(first.id(), purchases.payments(order.id()).getFirst().invoiceId());

        invoices.delete(order.id(), first.id());

        assertNull(purchases.payments(order.id()).getFirst().invoiceId());
        assertEquals(1, purchases.documents(order.id()).size(), "the file stays with the container");
        var page = resource.getPurchaseOrder(order.id());
        assertEquals(List.of(file.id()), page.invoiceFiles().stream().map(PurchaseInvoiceService.InvoiceFileView::documentId).toList());
        assertEquals(1, page.invoices().size());
    }

    @Test
    @TestTransaction
    void deletingTheFileKeepsTheInvoiceData() {
        PurchaseOrder order = container();
        PurchaseDocument file = file(order, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, "CI-2026-118.pdf");
        var invoice = invoices.add(order.id(), request(Payee.SUPPLIER, "CI-2026-118", "31048.04", file.id()));
        purchases.deleteDocument(order.id(), file.id());
        var after = invoices.list(order.id()).getFirst();
        assertEquals(invoice.id(), after.id());
        assertNull(after.documentId());
        assertNull(after.documentName());
        assertEquals("CI-2026-118", after.number());
    }

    @Test
    @TestTransaction
    void legacyInvoiceFilesAreListedWithTheirPayee() {
        PurchaseOrder order = container();
        PurchasePayment inspection = pay(order, "300", "Inspectie", Payee.SEPARATE);
        PurchasePayment courier = pay(order, "40", "Koerier", Payee.OTHER);
        var commercial = file(order, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, "invoice.pdf");
        var inspectionFile = file(order, PurchaseDocument.Kind.OTHER, "Factuur inspectie", "inspectie.pdf", inspection.id());
        var transport = file(order, PurchaseDocument.Kind.OTHER, "Factuur transport", "transport.pdf");
        var courierFile = file(order, PurchaseDocument.Kind.OTHER, "Factuur", "koerier.pdf", courier.id());
        var looseInvoice = file(order, PurchaseDocument.Kind.INVOICE, null, "los.pdf");
        file(order, PurchaseDocument.Kind.OTHER, "Foto", "foto.jpg");
        file(order, PurchaseDocument.Kind.PAYMENT_PROOF, null, "afschrift.pdf", inspection.id());

        var files = resource.getPurchaseOrder(order.id()).invoiceFiles();

        assertEquals(List.of(commercial.id(), inspectionFile.id(), transport.id(), courierFile.id(), looseInvoice.id()),
                files.stream().map(PurchaseInvoiceService.InvoiceFileView::documentId).toList());
        assertEquals(List.of(Payee.SUPPLIER, Payee.SEPARATE, Payee.LOGISTICS, Payee.OTHER, Payee.OTHER),
                files.stream().map(PurchaseInvoiceService.InvoiceFileView::payee).toList());
        assertEquals(inspection.id(), files.get(1).paymentId());
    }

    @Test
    @TestTransaction
    void aContainerWithInvoicesIsArchivedNotDeleted() {
        PurchaseOrder order = container();
        invoices.add(order.id(), request(Payee.SUPPLIER, "CI-1", "100", null));
        var refusal = assertThrows(BusinessRuleException.class, () -> purchases.delete(order.id()));
        assertEquals("Deze inkooporder heeft geregistreerde facturen; archiveer de container zodat de facturen behouden blijven",
                refusal.getMessage());
    }

    @Test
    @TestTransaction
    void aForeignInvoiceTakesTheRateItsPayeeIsBudgetedAt() {
        PurchaseOrder order = container();
        PurchaseOrderEntity entity = em.find(PurchaseOrderEntity.class, order.id());
        entity.usdToEurGoods = new BigDecimal("0.900000");
        entity.usdToEurTransport = new BigDecimal("0.800000");
        em.flush();
        em.clear();
        var road = invoices.add(order.id(), new InvoiceRequest(Payee.LOGISTICS, "FW-9", INVOICED,
                new BigDecimal("1000"), Currency.USD, null, null, null));
        assertEquals(new BigDecimal("800.00"), road.amountEur(), "the transport rate");
        var goods = invoices.add(order.id(), new InvoiceRequest(Payee.SUPPLIER, "CI-9", INVOICED,
                new BigDecimal("1000"), Currency.USD, null, null, null));
        assertEquals(new BigDecimal("900.00"), goods.amountEur(), "the goods rate");
        var given = invoices.add(order.id(), new InvoiceRequest(Payee.SUPPLIER, "CI-10", INVOICED,
                new BigDecimal("1000"), Currency.USD, new BigDecimal("876.54"), null, null));
        assertEquals(new BigDecimal("876.54"), given.amountEur(), "as given");
        /* Unchanged money keeps its euro value after the rate moved. */
        entity = em.find(PurchaseOrderEntity.class, order.id());
        entity.usdToEurGoods = new BigDecimal("0.700000");
        em.flush();
        var renamed = invoices.update(order.id(), goods.id(), new InvoiceRequest(Payee.SUPPLIER, "CI-9B", INVOICED,
                new BigDecimal("1000"), Currency.USD, null, null, "hernoemd"));
        assertEquals(new BigDecimal("900.00"), renamed.amountEur());
        assertEquals("hernoemd", renamed.notes());
    }

    @Test
    @TestTransaction
    void aForeignInvoiceOnAContainerWithoutRatesNeedsItsEuroAmount() {
        PurchaseOrder order = container();
        PurchaseOrderEntity entity = em.find(PurchaseOrderEntity.class, order.id());
        entity.usdToEurGoods = null;
        entity.usdToEurTransport = null;
        em.flush();
        em.clear();
        refused(order, "Vul de koers van de container in of geef het eurobedrag op", () -> new InvoiceRequest(
                Payee.LOGISTICS, "FW-9", INVOICED, new BigDecimal("1000"), Currency.USD, null, null, null));
    }

    @Test
    @TestTransaction
    void aPayeeChangeIsRefusedWhilePaymentsNameTheInvoice() {
        PurchaseOrder order = container();
        var invoice = invoices.add(order.id(), request(Payee.SUPPLIER, "CI-1", "100", null));
        purchases.addPayment(order.id(), LocalDate.of(2026, 9, 1), new BigDecimal("50"), Currency.EUR, "Deel",
                Payee.SUPPLIER, false, null, null, invoice.id(), false);
        var refusal = assertThrows(BusinessRuleException.class, () -> invoices.update(order.id(), invoice.id(),
                request(Payee.LOGISTICS, "CI-1", "100", null)));
        assertEquals("Er zijn betalingen aan deze factuur gekoppeld; kies bij die betalingen eerst een andere factuur",
                refusal.getMessage());
    }

    @Test
    @TestTransaction
    void theListEndpointCarriesEveryContainersInvoicesAndFiles() {
        PurchaseOrder first = container();
        PurchaseOrder second = container();
        PurchaseDocument file = file(first, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, "CI-2026-118.pdf");
        invoices.add(first.id(), request(Payee.SUPPLIER, "CI-2026-118", "31048.04", file.id()));
        file(second, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, "invoice.pdf");

        var list = resource.listPurchaseOrders();

        var firstView = list.stream().filter(view -> view.order().id().equals(first.id())).findFirst().orElseThrow();
        var secondView = list.stream().filter(view -> view.order().id().equals(second.id())).findFirst().orElseThrow();
        assertEquals("CI-2026-118", firstView.invoices().getFirst().number());
        assertEquals("CI-2026-118.pdf", firstView.invoices().getFirst().documentName());
        assertTrue(firstView.invoiceFiles().isEmpty());
        assertTrue(secondView.invoices().isEmpty());
        assertEquals("invoice.pdf", secondView.invoiceFiles().getFirst().originalFilename());
    }

    private void refused(PurchaseOrder order, String message, Supplier<InvoiceRequest> request) {
        var refusal = assertThrows(BusinessRuleException.class, () -> invoices.add(order.id(), request.get()));
        assertEquals(message, refusal.getMessage());
    }

    private static InvoiceRequest request(Payee payee, String number, String amount, Long documentId) {
        return new InvoiceRequest(payee, number, INVOICED, new BigDecimal(amount), Currency.EUR, null, documentId, null);
    }

    private PurchaseOrder container() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Invoice Co", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }

    private PurchasePayment pay(PurchaseOrder order, String amount, String label, Payee payee) {
        return purchases.addPayment(order.id(), LocalDate.of(2026, 8, 2), new BigDecimal(amount), Currency.EUR,
                label, payee);
    }

    private PurchaseDocument file(PurchaseOrder order, PurchaseDocument.Kind kind, String label, String name) {
        return file(order, kind, label, name, null);
    }

    private PurchaseDocument file(PurchaseOrder order, PurchaseDocument.Kind kind, String label, String name, Long paymentId) {
        return purchases.addDocument(order.id(), kind, label, paymentId, name, "application/pdf",
                ("%PDF-1.4 " + name).getBytes());
    }
}
