package be.enrosed.sourcing.application;

import be.enrosed.finance.banking.BankStatementService;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Currency;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogEntity;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.adapter.in.rest.SourcingResource;
import be.enrosed.sourcing.application.PurchaseInvoiceService.InvoiceRequest;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 2 (R2-3): one invoice for every part-payment, and the invoice file uploaded to the invoice. */
@QuarkusTest
@TestSecurity(user = "fixture-admin", roles = "admin")
class PurchaseInvoiceAssignPersistenceTest {
    @Inject PurchaseOrderService purchases;
    @Inject PurchaseInvoiceService invoices;
    @Inject SupplierService suppliers;
    @Inject SourcingResource resource;
    @Inject BankStatementService bank;

    private static final LocalDate INVOICED = LocalDate.of(2026, 8, 20);

    @Test
    @TestTransaction
    void oneCallAssignsTheInvoiceToEveryPaymentOfThePayeeTransfersAndBankLinesIncluded() throws Exception {
        PurchaseOrder order = container();
        PurchasePayment deposit = purchases.addPayment(order.id(), LocalDate.of(2026, 8, 2), new BigDecimal("9314.41"),
                Currency.EUR, "Aanbetaling 30%", Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
        PurchasePayment balance = purchases.addPayment(order.id(), LocalDate.of(2026, 9, 10), new BigDecimal("21733.63"),
                Currency.EUR, "Saldo 70%", Payee.SUPPLIER, false, PaymentTerms.Moment.SHIPPED);
        List<PurchasePayment> parts = purchases.splitPayment(order.id(), balance.id(), List.of(
                new PurchaseOrderService.PaymentPart(new BigDecimal("10000.00"), new BigDecimal("10000.00"), LocalDate.of(2026, 9, 10)),
                new PurchaseOrderService.PaymentPart(new BigDecimal("11733.63"), new BigDecimal("11733.63"), LocalDate.of(2026, 9, 24))));
        PurchasePayment transport = purchases.addPayment(order.id(), LocalDate.of(2026, 9, 1), new BigDecimal("1500"),
                Currency.EUR, "Transport", Payee.LOGISTICS);
        long line = bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", new BigDecimal("-9314.41"), Instant.parse("2026-08-02T08:00:00Z"), "Europe/Brussels",
                "Aanbetaling", "Supplier", Instant.parse("2026-08-02T08:00:00Z"), false)).id();
        bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(deposit.id()), null, null));
        var proforma = invoices.add(order.id(), request(Payee.SUPPLIER, "PI-2026-007", "31048.04"));
        var commercial = invoices.add(order.id(), request(Payee.SUPPLIER, "CI-2026-118", "31048.04"));
        var freight = invoices.add(order.id(), request(Payee.LOGISTICS, "TR-55", "1500"));
        PurchasePayment later = purchases.addPayment(order.id(), LocalDate.of(2026, 9, 30), new BigDecimal("50"),
                Currency.EUR, "Correctie", Payee.SUPPLIER);
        assertEquals(List.of(deposit.id(), parts.get(0).id(), parts.get(1).id()), view(order, proforma.id()).paymentIds(),
                "the second invoice pinned the payments on the first");

        var views = resource.assignInvoiceToAll(order.id(), commercial.id());

        var assigned = views.stream().filter(view -> view.id().equals(commercial.id())).findFirst().orElseThrow();
        assertEquals(List.of(deposit.id(), parts.get(0).id(), parts.get(1).id(), later.id()), assigned.paymentIds(),
                "every supplier payment, both transfers of the balance and the one on the bank included");
        assertEquals(new BigDecimal("31098.04"), assigned.paidEur());
        assertEquals(List.of(), views.stream().filter(view -> view.id().equals(proforma.id())).findFirst().orElseThrow().paymentIds());
        assertEquals(List.of(transport.id()), views.stream().filter(view -> view.id().equals(freight.id())).findFirst().orElseThrow().paymentIds(),
                "another payee's payments stay where they are");
        for (PurchasePayment payment : purchases.payments(order.id())) {
            if (payment.payee() == Payee.SUPPLIER) assertEquals(commercial.id(), payment.invoiceId(), payment.label());
            else assertNull(payment.invoiceId(), payment.label());
        }
        assertEquals(line, purchases.payments(order.id()).stream().filter(payment -> payment.id().equals(deposit.id()))
                .findFirst().orElseThrow().bankStatementLineId(), "the bank link stays");
        assertEquals(1, activities(order, "Factuur CI-2026-118 toegewezen aan alle betalingen van Leverancier"));

        resource.assignInvoiceToAll(order.id(), commercial.id());
        assertEquals(1, activities(order, "Factuur CI-2026-118 toegewezen aan alle betalingen van Leverancier"),
                "nothing moved, nothing logged");
        assertThrows(NotFoundException.class, () -> invoices.assignToAll(order.id(), 999999L));
    }

    @Test
    @TestTransaction
    void anInvoiceFileGoesToTheInvoiceNeverToAPayment() {
        PurchaseOrder order = container();
        PurchasePayment deposit = purchases.addPayment(order.id(), LocalDate.of(2026, 8, 2), new BigDecimal("9314.41"),
                Currency.EUR, "Aanbetaling 30%", Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
        var first = invoices.add(order.id(), request(Payee.SUPPLIER, "PI-2026-007", "31048.04"));
        int before = purchases.documents(order.id()).size();
        var missing = assertThrows(BusinessRuleException.class, () -> invoices.addWithFile(order.id(),
                new InvoiceRequest(Payee.SUPPLIER, " ", INVOICED, new BigDecimal("10"), null, null, null, null),
                "CI.pdf", "application/pdf", bytes("CI"), true));
        assertEquals("Vul het factuurnummer in", missing.getMessage());
        assertEquals(before, purchases.documents(order.id()).size(), "refused data keeps no file");

        var created = invoices.addWithFile(order.id(), request(Payee.SUPPLIER, "CI-2026-118", "31048.04"),
                "CI-2026-118.pdf", "application/pdf", bytes("CI-2026-118"), true);

        assertEquals("CI-2026-118.pdf", created.documentName());
        PurchaseDocument file = purchases.documents(order.id()).stream()
                .filter(document -> document.id().equals(created.documentId())).findFirst().orElseThrow();
        assertEquals(PurchaseDocument.Kind.COMMERCIAL_INVOICE, file.kind());
        assertNull(file.paymentId(), "the file belongs to the container, not to one payment");
        assertEquals(List.of(deposit.id()), created.paymentIds(), "voor alle deelbetalingen");
        assertEquals(List.of(), view(order, first.id()).paymentIds());
        assertTrue(resource.getPurchaseOrder(order.id()).invoiceFiles().isEmpty(), "a held file is no loose invoice file");

        var transport = invoices.add(order.id(), request(Payee.LOGISTICS, "TR-55", "1500"));
        var attached = invoices.attachFile(order.id(), transport.id(), "transport.pdf", "application/pdf",
                bytes("transport"), false);
        assertEquals("transport.pdf", attached.documentName());
        assertEquals(PurchaseDocument.Kind.INVOICE, purchases.documents(order.id()).stream()
                .filter(document -> document.id().equals(attached.documentId())).findFirst().orElseThrow().kind());
        var again = assertThrows(BusinessRuleException.class, () -> invoices.attachFile(order.id(), transport.id(),
                "transport-2.pdf", "application/pdf", bytes("again"), false));
        assertEquals("Bij factuur TR-55 hoort al een bestand (transport.pdf); verwijder dat eerst", again.getMessage());
    }

    /**
     * Review of round 2: the inspection paid through the supplier goes along with the supplier's payments, so the
     * proforma keeps no part of them and the invoice assigned to all of them is the one covering the inspection.
     */
    @Test
    @TestTransaction
    void theInspectionPaidThroughTheSupplierGoesAlong() {
        PurchaseOrder order = container();
        purchases.setInspectionViaSupplier(order.id(), true);
        PurchasePayment goods = purchases.addPayment(order.id(), LocalDate.of(2026, 8, 2), new BigDecimal("10000"),
                Currency.EUR, "Aanbetaling 30%", Payee.SUPPLIER, false, PaymentTerms.Moment.ORDERED);
        PurchasePayment inspection = purchases.addPayment(order.id(), LocalDate.of(2026, 8, 2), new BigDecimal("300"),
                Currency.EUR, "Inspectie", Payee.SEPARATE);
        /* A lab fee paid in a transfer of its own is not paid through the supplier: it stays where it is. */
        PurchasePayment lab = purchases.addPayment(order.id(), LocalDate.of(2026, 8, 5), new BigDecimal("80"),
                Currency.EUR, "Labo", Payee.SEPARATE);
        long line = bank.importExternal(new BankStatementService.ExternalRequest(UUID.randomUUID().toString(),
                "BE00000000000000", new BigDecimal("-80.00"), Instant.parse("2026-08-05T08:00:00Z"), "Europe/Brussels",
                "Labo", "Lab", Instant.parse("2026-08-05T08:00:00Z"), false)).id();
        bank.linkExisting(line, new BankStatementService.OutgoingLinks(List.of(lab.id()), null, null));
        var proforma = invoices.add(order.id(), request(Payee.SUPPLIER, "PI-1", "31048.04"));
        var commercial = invoices.add(order.id(), request(Payee.SUPPLIER, "CI-1", "31048.04"));
        assertTrue(view(order, proforma.id()).coversInspection(), "the primary covers it until another is assigned");

        var views = invoices.assignToAll(order.id(), commercial.id());

        var assigned = views.stream().filter(view -> view.id().equals(commercial.id())).findFirst().orElseThrow();
        var first = views.stream().filter(view -> view.id().equals(proforma.id())).findFirst().orElseThrow();
        assertEquals(List.of(goods.id(), inspection.id()), assigned.paymentIds());
        assertEquals(new BigDecimal("10300.00"), assigned.paidEur());
        assertTrue(assigned.coversInspection());
        assertEquals(List.of(), first.paymentIds(), "the proforma keeps no part of the payments");
        assertEquals(0, first.paidEur().signum());
        assertTrue(first.primary());
        assertTrue(!first.coversInspection(), "the inspection went with the payments");
        assertNull(purchases.payments(order.id()).stream().filter(payment -> payment.id().equals(lab.id()))
                .findFirst().orElseThrow().invoiceId());
    }

    /** Cosmetic: the list names an invoice's file even when that file was filed as another kind. */
    @Test
    @TestTransaction
    void theListNamesAnInvoicesFileOfAnyKind() {
        PurchaseOrder order = container();
        PurchaseDocument packing = purchases.addDocument(order.id(), PurchaseDocument.Kind.PACKING_LIST, null, null,
                "paklijst-en-factuur.pdf", "application/pdf", bytes("PL"));
        invoices.add(order.id(), new InvoiceRequest(Payee.SUPPLIER, "CI-PL", INVOICED, new BigDecimal("100"), null,
                null, packing.id(), null));

        var listed = resource.listPurchaseOrders().stream().filter(view -> view.order().id().equals(order.id()))
                .findFirst().orElseThrow();

        assertEquals("paklijst-en-factuur.pdf", listed.invoices().getFirst().documentName());
        assertEquals(resource.getPurchaseOrder(order.id()).invoices().getFirst().documentName(),
                listed.invoices().getFirst().documentName(), "the list and the single container agree");
        assertTrue(listed.invoiceFiles().isEmpty());
    }

    private PurchaseInvoiceService.PurchaseInvoiceView view(PurchaseOrder order, Long invoiceId) {
        return invoices.list(order.id()).stream().filter(view -> view.id().equals(invoiceId)).findFirst().orElseThrow();
    }

    private static long activities(PurchaseOrder order, String summary) {
        return ActivityLogEntity.count("entityType = ?1 and entityId = ?2 and summary = ?3",
                ActivityLogService.ENTITY_PURCHASE_ORDER, order.id().toString(), summary);
    }

    private static InvoiceRequest request(Payee payee, String number, String amount) {
        return new InvoiceRequest(payee, number, INVOICED, new BigDecimal(amount), Currency.EUR, null, null, null);
    }

    private static byte[] bytes(String name) {
        return ("%PDF-1.4 " + name).getBytes();
    }

    private PurchaseOrder container() {
        var supplier = suppliers.save(new be.enrosed.sourcing.domain.Supplier(null, "Assign Co", "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        return purchases.create(supplier.id(), new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
    }
}
