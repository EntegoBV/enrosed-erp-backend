package be.enrosed.finance.invoices;

import be.enrosed.finance.application.CompanyCostService;
import be.enrosed.finance.application.CostPartyService;
import be.enrosed.finance.banking.BankStatementLineEntity;
import be.enrosed.finance.domain.CompanyCost;
import be.enrosed.finance.domain.CostParty;
import be.enrosed.finance.domain.VatRegime;
import be.enrosed.finance.odoo.OdooCostImportEntity;
import be.enrosed.finance.odoo.OdooDocumentService;
import be.enrosed.finance.odoo.OdooInvoiceSearch;
import be.enrosed.finance.odoo.OdooOutboxEntity;
import be.enrosed.finance.odoo.OdooTicaDocumentEntity;
import be.enrosed.media.MediaKind;
import be.enrosed.media.MediaRole;
import be.enrosed.media.MediaService;
import be.enrosed.media.MediaTargetType;
import be.enrosed.media.MediaDtos;
import be.enrosed.media.MediaUploadPolicy;
import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.IncomingPaymentService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;
import be.enrosed.sourcing.adapter.out.persistence.PurchaseInvoiceEntity;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities;
import be.enrosed.sourcing.application.PurchaseInvoiceService;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.application.SupplierService;
import be.enrosed.sourcing.domain.PurchaseDocument;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchasePayment.Payee;
import be.enrosed.sourcing.domain.Supplier;
import be.enrosed.tica.TicaSettlementEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Committed fixtures for the finder's {@code @QuarkusTest}s (the finder reads in its own transactions, so nothing may sit
 * in a rolled-back test transaction). Every row carries this run's token and is dated in March 2031; assertions read
 * through {@code q=token}, an {@code orderId} or a 2031 period and compare only these keys.
 */
final class InvoiceFinderFixtures {
    record Deps(EntityManager em, SupplierService suppliers, PurchaseOrderService purchases, PurchaseInvoiceService invoices,
                CompanyCostService costs, CostPartyService parties, MediaService media, SalesOrderService sales,
                CustomerService customers, IncomingPaymentService incoming, OdooDocumentMarks marks) {}

    static final LocalDate MARCH_FIRST = LocalDate.of(2031, 3, 1);
    static final LocalDate MARCH_LAST = LocalDate.of(2031, 3, 31);

    final String token = "ZQX" + ThreadLocalRandom.current().nextInt(100000, 1000000);
    final String namespace = "t-" + token.toLowerCase();
    final long base = 100_000_000L + ThreadLocalRandom.current().nextLong(1, 800_000) * 100;
    final FakeOdooInvoices odoo = new FakeOdooInvoices(namespace);

    long supplierId;
    PurchaseOrder orderA;
    long orderTrashed;
    long ciInvoice, fwInvoice, ciDocument, piDocument, trashedInvoice, ciLine;
    long costWithFile, costNoFile, costRecurring, costOdoo, costTica, costVt1, costDeleted, ticaSettlement;
    long pdfAsset, pngAsset;
    long salesInvoice, salesCredit, salesConcept, salesCancelled, salesLine;
    String salesInvoiceNumber;
    /** M1 VT1 € 1.250 (matches costVt1) · M2 VT1 € 1.260 (new) · M3 FW1 € 410 (matches fwInvoice) · M4 registered ·
     *  M5 binding of a deleted cost · M6 TICA settled · M7 TICA open · M8 marked HANDLED · M9 marked CONTAINER (orderA) ·
     *  M10 outbox · M11 our invoice number · M12 credit note naming our invoice · M13 cancelled. */
    long m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12, m13;

    static LocalDate d(int day) {
        return LocalDate.of(2031, 3, day);
    }

    static Instant at(int day) {
        return Instant.parse(String.format("2031-03-%02dT09:00:00Z", day));
    }

    InvoiceFinderFixtures build(Deps deps) {
        m1 = base + 1; m2 = base + 2; m3 = base + 3; m4 = base + 4; m5 = base + 5; m6 = base + 6; m7 = base + 7;
        m8 = base + 8; m9 = base + 9; m10 = base + 10; m11 = base + 11; m12 = base + 12; m13 = base + 13;
        EntityManager em = deps.em();

        // Containers: one live with a supplier invoice (file + payment on a bank line), a forwarder invoice without file
        // and a proforma file without data; one trashed with an invoice.
        Supplier supplier = deps.suppliers().save(new Supplier(null, "Finder " + token, "CN", "Yiwu", null, null, null,
                Currency.USD, "FOB", "Ningbo", 30, null));
        supplierId = supplier.id();
        PurchaseOrder created = deps.purchases().create(supplierId, new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        long orderId = created.id();
        QuarkusTransaction.requiringNew().run(() ->
                em.find(SourcingEntities.PurchaseOrderEntity.class, orderId).alias = "Voorjaar " + token);
        orderA = QuarkusTransaction.requiringNew().call(() -> deps.purchases().get(orderId));
        ciLine = bankLine(em, "ci", "-100.00", 5, null);
        PurchaseDocument ci = deps.purchases().addDocument(orderId, PurchaseDocument.Kind.COMMERCIAL_INVOICE, null, null,
                "CI-" + token + "-118.pdf", "application/pdf", ("%PDF-1.4 CI " + token).getBytes());
        ciDocument = ci.id();
        var payment = deps.purchases().addPayment(orderId, LocalDate.of(2026, 8, 2), new BigDecimal("100.00"), Currency.EUR,
                "Aanbetaling", Payee.SUPPLIER);
        QuarkusTransaction.requiringNew().run(() ->
                em.find(SourcingEntities.PurchasePaymentEntity.class, payment.id()).bankStatementLineId = ciLine);
        ciInvoice = deps.invoices().add(orderId, new PurchaseInvoiceService.InvoiceRequest(Payee.SUPPLIER, "CI-" + token + "-118",
                LocalDate.of(2026, 8, 20), new BigDecimal("1000.00"), Currency.EUR, null, ciDocument, "notitie eerste")).id();
        fwInvoice = deps.invoices().add(orderId, new PurchaseInvoiceService.InvoiceRequest(Payee.LOGISTICS, token + "-FW1",
                LocalDate.of(2026, 8, 20), new BigDecimal("410.00"), Currency.EUR, null, null, null)).id();
        piDocument = deps.purchases().addDocument(orderId, PurchaseDocument.Kind.PROFORMA_INVOICE, null, null,
                token + "-PI.pdf", "application/pdf", ("%PDF-1.4 PI " + token).getBytes()).id();
        PurchaseOrder trashed = deps.purchases().create(supplierId, new BigDecimal("0.14"), new BigDecimal("0.90"), BigDecimal.ZERO);
        orderTrashed = trashed.id();
        trashedInvoice = deps.invoices().add(orderTrashed, new PurchaseInvoiceService.InvoiceRequest(Payee.SUPPLIER, token + "-TR1",
                LocalDate.of(2026, 8, 20), new BigDecimal("50.00"), Currency.EUR, null, null, null)).id();
        QuarkusTransaction.requiringNew().run(() -> {
            em.find(PurchaseInvoiceEntity.class, ciInvoice).invoiceDate = d(10);
            em.find(PurchaseInvoiceEntity.class, fwInvoice).invoiceDate = d(9);
            em.find(PurchaseInvoiceEntity.class, trashedInvoice).invoiceDate = d(7);
            em.find(SourcingEntities.PurchaseDocumentEntity.class, piDocument).addedAt = at(8);
            em.createNativeQuery("update purchase_order set deleted_at = ?1 where id = ?2")
                    .setParameter(1, Instant.now()).setParameter(2, orderTrashed).executeUpdate();
        });

        // Costs: a party with an alias, files, recurring, Odoo-registered, TICA-settled, a hand-entered Peppol bill, deleted.
        CostParty vander = deps.parties().create(new CostParty(null, "Vandersanden " + token, null, null, null, null, null, null,
                null, null, List.of()));
        CostParty oost = deps.parties().create(new CostParty(null, "Oostkust " + token, null, null, null, null, null, null,
                null, null, List.of()));
        deps.parties().merge(oost.id(), vander.id());
        costWithFile = deps.costs().create(cost("met bestand " + token, d(6), "Vandersanden " + token, token + "-K1",
                "100.00", "121.00", null, null)).id();
        var pdf = deps.media().upload("factuur " + token + ".pdf", new MediaUploadPolicy.ValidatedFile("factuur-" + token + ".pdf",
                "application/pdf", MediaKind.DOCUMENT, ("%PDF-1.4 kost " + token).getBytes()));
        pdfAsset = pdf.asset().id();
        deps.media().link(pdfAsset, new MediaDtos.LinkRequest(MediaTargetType.COMPANY_COST, costWithFile, MediaRole.INVOICE));
        var png = deps.media().upload("bon " + token, new MediaUploadPolicy.ValidatedFile("bon-" + token + ".png", "image/png",
                MediaKind.IMAGE, png(token)));
        pngAsset = png.asset().id();
        costNoFile = deps.costs().create(cost("zonder bestand " + token, d(10), "Drukkerij " + token, token + "-K2",
                "10.00", "12.10", null, null)).id();
        costRecurring = deps.costs().create(cost("vast " + token, d(12), "Huur " + token, token + "-K3",
                "20.00", "24.20", 999_999_999L, null)).id();
        costOdoo = deps.costs().create(cost("odoo " + token, d(3), "Partner " + token, token + "-OD1",
                "30.00", "36.30", null, null)).id();
        costTica = deps.costs().create(cost("tica " + token, d(2), "TICA " + token, token + "-TC1",
                "40.00", "48.40", null, d(2))).id();
        costVt1 = deps.costs().create(cost("hand " + token, d(20), "Verhoeven " + token, token + "-VT1",
                "1033.06", "1250.00", null, null)).id();
        costDeleted = deps.costs().create(cost("weg " + token, d(1), "Weg " + token, token + "-RM1",
                "5.00", "6.05", null, null)).id();
        deps.costs().delete(costDeleted);
        QuarkusTransaction.requiringNew().run(() -> {
            binding(em, m4, costOdoo, "{\"document\":{\"peppolState\":\"done\"}}");
            binding(em, m5, costDeleted, null);
            TicaSettlementEntity settlement = new TicaSettlementEntity();
            settlement.reference = token + "-TS";
            settlement.location = "Stand " + token;
            settlement.documentDate = d(2);
            settlement.dueDate = d(2);
            settlement.periodFrom = d(1);
            settlement.periodTo = d(2);
            settlement.salesExcl = BigDecimal.ZERO;
            settlement.salesVat = BigDecimal.ZERO;
            settlement.commissionExcl = new BigDecimal("40.00");
            settlement.commissionVat = new BigDecimal("8.40");
            settlement.netReceivable = BigDecimal.ZERO;
            settlement.commissionCostId = costTica;
            settlement.createdAt = Instant.now();
            em.persist(settlement);
            em.flush();
            ticaSettlement = settlement.id;
            tica(em, m6, ticaSettlement);
            tica(em, m7, null);
        });

        // Sales: an issued invoice (partly paid, on a bank line, sent through the outbox), an issued credit note, a concept
        // and a cancelled invoice.
        long customer = deps.customers().create(new Customer(null, "Klant " + token, "Fin", null, null, "BE0000000000", "BE",
                Language.NL, "Main 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now())).id();
        salesInvoice = issued(deps, customer, "100");
        SalesOrder credit = deps.sales().createCreditNote(salesInvoice, new SalesOrderService.CreditNoteRequest(CreditReason.OTHER,
                List.of(), List.of(new SalesOrderService.CreditAmount("Korting", new BigDecimal("40"))), false, null));
        salesCredit = deps.sales().issueInvoice(credit.id()).id();
        salesConcept = deps.sales().create(customer, "BE", "DAP", DocumentType.FACTUUR).id();
        salesCancelled = issued(deps, customer, "50");
        deps.incoming().add(salesInvoice, new IncomingPaymentService.Request(new BigDecimal("21.00"),
                Instant.parse("2026-09-20T10:00:00Z"), "Europe/Brussels", "Bank"));
        QuarkusTransaction.requiringNew().run(() -> {
            SalesOrderEntity invoice = em.find(SalesOrderEntity.class, salesInvoice);
            invoice.orderDate = d(12);
            invoice.invoiceDueDate = d(28);
            em.find(SalesOrderEntity.class, salesCredit).orderDate = d(11);
            em.find(SalesOrderEntity.class, salesConcept).orderDate = d(12);
            SalesOrderEntity cancelled = em.find(SalesOrderEntity.class, salesCancelled);
            cancelled.orderDate = d(12);
            cancelled.status = QuoteStatus.GEANNULEERD;
            OdooOutboxEntity outbox = new OdooOutboxEntity();
            outbox.salesOrderId = salesInvoice;
            outbox.sourceNamespace = namespace;
            outbox.marker = "finder-" + token;
            outbox.snapshotHash = "h";
            outbox.snapshotJson = "{}";
            outbox.state = "SENT";
            outbox.odooMoveId = m10;
            outbox.peppolState = "done";
            outbox.updatedAt = Instant.now();
            em.persist(outbox);
        });
        salesLine = bankLine(em, "so", "21.00", 13, salesInvoice);
        salesInvoiceNumber = QuarkusTransaction.requiringNew().call(() -> deps.sales().get(salesInvoice).number());

        // Marks.
        deps.marks().put(namespace, m8, OdooDocumentMarks.HANDLED, null, null, "test");
        deps.marks().put(namespace, m9, OdooDocumentMarks.CONTAINER, orderId, "zit in " + orderA.number(), "test");

        // Odoo moves.
        String partner = "Partner " + token;
        odoo.moves.addAll(List.of(
                move(m1, "in_invoice", "posted", token + "-VT1", null, partner, 20, "1250.00", true),
                move(m2, "in_invoice", "posted", token + "-VT1", null, partner, 19, "1260.00", true),
                move(m3, "in_invoice", "posted", token + "-FW1", null, partner, 18, "410.00", false),
                move(m4, "in_invoice", "posted", token + "-OD1", null, partner, 17, "36.30", false),
                move(m5, "in_invoice", "posted", token + "-RM1", null, partner, 16, "6.05", false),
                move(m6, "in_refund", "posted", token + "-TICA1", null, partner, 15, "12.00", false),
                move(m7, "in_refund", "posted", token + "-TICA2", null, partner, 14, "13.00", false),
                move(m8, "in_invoice", "posted", token + "-H1", null, partner, 13, "14.00", false),
                move(m9, "in_invoice", "posted", token + "-C1", null, partner, 12, "15.00", false),
                outMove(m10, "out_invoice", "INV/" + token + "/1", null, partner, 11),
                outMove(m11, "out_invoice", salesInvoiceNumber, null, partner, 10),
                outMove(m12, "out_refund", "RINV/" + token + "/1", salesInvoiceNumber, partner, 9),
                move(m13, "in_invoice", "cancel", token + "-X1", null, partner, 8, "16.00", false)));
        return this;
    }

    /**
     * Takes the fixtures out of the lists other suites read "first" or count statements over: the sales documents are
     * trashed, the outbox row and both bank lines removed. Costs, containers and marks stay (token-named, 2031).
     */
    void tearDown(EntityManager em) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (long id : new long[] {salesInvoice, salesCredit, salesConcept, salesCancelled})
                em.createNativeQuery("update sales_order set deleted_at = ?1 where id = ?2")
                        .setParameter(1, Instant.now()).setParameter(2, id).executeUpdate();
            em.createNativeQuery("delete from odoo_outbox where sales_order_id = ?1").setParameter(1, salesInvoice).executeUpdate();
            for (long line : new long[] {ciLine, salesLine}) {
                em.createNativeQuery("update purchase_payment set bank_statement_line_id = null where bank_statement_line_id = ?1")
                        .setParameter(1, line).executeUpdate();
                em.createNativeQuery("delete from bank_statement_line where id = ?1").setParameter(1, line).executeUpdate();
            }
        });
    }

    private static CompanyCost cost(String description, LocalDate date, String party, String reference, String excl,
                                    String incl, Long recurringCostId, LocalDate paidOn) {
        return new CompanyCost(null, date, "SOFTWARE", description, party, new BigDecimal(excl), new BigDecimal(incl),
                new BigDecimal("21"), reference, paidOn, null, null, null, recurringCostId, null, VatRegime.BE, true, "BE",
                null, null, null, null, null, null, null);
    }

    private long bankLine(EntityManager em, String what, String amount, int day, Long salesOrderId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            BankStatementLineEntity line = new BankStatementLineEntity();
            line.account = "BE68539007547034";
            line.fingerprint = "finder-" + token + "-" + what;
            line.amountEur = new BigDecimal(amount);
            line.bookedAt = at(day);
            line.timeZone = "Europe/Brussels";
            line.reference = "Finder " + token;
            line.recordedAt = Instant.now();
            line.salesOrderId = salesOrderId;
            em.persist(line);
            em.flush();
            return line.id;
        });
    }

    private void binding(EntityManager em, long moveId, long costId, String snapshotJson) {
        OdooCostImportEntity row = new OdooCostImportEntity();
        row.id = namespace + ":" + moveId;
        row.namespace = namespace;
        row.companyId = 1;
        row.moveId = moveId;
        row.companyCostId = costId;
        row.snapshotJson = snapshotJson;
        row.createdAt = Instant.now();
        row.registeredAt = Instant.now();
        em.persist(row);
    }

    private void tica(EntityManager em, long moveId, Long settlementId) {
        OdooTicaDocumentEntity row = new OdooTicaDocumentEntity();
        row.id = namespace + ":" + moveId;
        row.sourceNamespace = namespace;
        row.moveId = moveId;
        row.reference = "TICA " + token;
        row.lastSeenAt = Instant.now();
        row.settlementId = settlementId;
        row.code = settlementId == null ? "PENDING" : "IMPORTED";
        em.persist(row);
    }

    private long issued(Deps deps, long customer, String amount) {
        var created = deps.sales().create(customer, "BE", "DAP", DocumentType.FACTUUR);
        QuarkusTransaction.requiringNew().run(() -> {
            var stored = deps.em().find(SalesOrderEntity.class, created.id());
            stored.extraLinesJson = "[{\"description\":\"Service\",\"quantity\":1,\"unitPriceEur\":" + amount + "}]";
            stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
            stored.manualFreightEur = BigDecimal.ZERO;
            stored.freight = FreightState.AANGEVULD;
            stored.status = QuoteStatus.UITGEREIKT;
        });
        return created.id();
    }

    private static OdooInvoiceSearch.Move move(long id, String type, String state, String ref, String origin, String partner,
                                               int day, String amount, boolean pdf) {
        List<OdooDocumentService.Attachment> files = pdf
                ? List.of(new OdooDocumentService.Attachment(id * 10 + 1, ref + ".pdf", "application/pdf", 1200, "PDF"),
                          new OdooDocumentService.Attachment(id * 10 + 2, ref + ".xml", "application/xml", 800, "XML"))
                : List.of();
        return new OdooInvoiceSearch.Move(id, type, state, "not_paid", "BILL/" + id, ref, origin, 77L, partner, d(day), d(day),
                d(day).plusDays(30), "EUR", new BigDecimal(amount), new BigDecimal(amount), pdf ? "done" : null, files);
    }

    private static OdooInvoiceSearch.Move outMove(long id, String type, String name, String origin, String partner, int day) {
        return new OdooInvoiceSearch.Move(id, type, "posted", "not_paid", name, "", origin, 78L, partner, d(day), d(day),
                d(day).plusDays(30), "EUR", new BigDecimal("121.00"), new BigDecimal("121.00"), null, List.of());
    }

    /** A valid 1×1 PNG whose bytes differ per run (the library deduplicates by content). */
    private static byte[] png(String token) {
        try {
            var image = new java.awt.image.BufferedImage(1 + Math.abs(token.hashCode() % 50), 1,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
