package be.enrosed.shared.trash;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.CustomerService;
import be.enrosed.sales.application.QuoteService;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A deleted concept credit note is its own kind in the trash and only comes back while its invoice still has room. */
@QuarkusTest
@TestSecurity(user = "emre", roles = "admin")
class CreditNoteTrashTest {
    @Inject DeletedItemsService trash;
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject CustomerService customers;
    @Inject EntityManager em;

    @Test @TestTransaction
    void restoreWaitsUntilAReopenedInvoiceIsIssuedAgain() {
        var invoice = issued();
        var cn = credit(invoice, "60");
        sales.delete(cn.id());
        var row = item(cn.id());
        assertEquals(QuoteStatus.CONCEPT, quotes.reopen(invoice.id()).status(), "a trashed credit note does not hold its invoice");
        assertEquals("Reik factuur " + invoice.number() + " eerst uit.", trash.detail(row.id()).blockReason());
        assertFalse(trash.detail(row.id()).restoreAllowed());
        assertThrows(BusinessRuleException.class, () -> trash.restore(row.id()));

        em.find(SalesOrderEntity.class, invoice.id()).status = QuoteStatus.UITGEREIKT;
        em.flush(); em.clear();
        assertTrue(trash.detail(row.id()).restoreAllowed());
        trash.restore(row.id());
        assertEquals(invoice.id(), sales.get(cn.id()).creditedInvoiceId());
        assertEquals(1, sales.liveCreditNotesOf(invoice.id()).size());
    }

    @Test @TestTransaction
    void aCreditNoteIsTrashedAsItsOwnKindAndRestoredWithItsInvoiceLink() {
        var invoice = issued();
        var cn = credit(invoice, "60");
        sales.delete(cn.id());
        var row = item(cn.id());
        assertEquals(DeletedItemDtos.Type.CREDIT_NOTE, row.type());
        assertTrue(row.restoreAllowed(), row.blockReason());
        assertEquals(cn.number(), row.number());
        trash.restore(row.id());
        var restored = sales.get(cn.id());
        assertTrue(restored.isCreditNote());
        assertEquals(invoice.id(), restored.creditedInvoiceId());
        assertEquals(CreditReason.OTHER, restored.creditReason());
        assertEquals(QuoteStatus.CONCEPT, restored.status());
        assertEquals(1L, ((Number) em.createNativeQuery("select count(*) from activity_log where entity_id = :id and action = 'RESTORED'")
                .setParameter("id", Long.toString(cn.id())).getSingleResult()).longValue());
    }

    @Test @TestTransaction
    void restoreIsRefusedWhenTheInvoiceIsGoneDeadOrAlreadyFullyCredited() {
        var invoice = issued();
        var cn = credit(invoice, "100");
        sales.delete(cn.id());
        var row = item(cn.id());

        var replacement = credit(invoice, "100");
        assertEquals("Het tegoed op " + invoice.number() + " is inmiddels al gecrediteerd. Maak een nieuwe creditnota.",
                trash.detail(row.id()).blockReason());
        assertFalse(trash.detail(row.id()).restoreAllowed());
        sales.delete(replacement.id());
        assertTrue(trash.detail(row.id()).restoreAllowed());

        em.find(SalesOrderEntity.class, invoice.id()).status = QuoteStatus.GEANNULEERD;
        em.flush(); em.clear();
        assertEquals("Factuur " + invoice.number() + " is niet meer actief.", trash.detail(row.id()).blockReason());

        em.createNativeQuery("update sales_order set deleted_at = :at where id = :id").setParameter("at", Instant.now())
                .setParameter("id", invoice.id()).executeUpdate();
        em.clear();
        assertEquals("Herstel eerst de oorspronkelijke factuur.", trash.detail(row.id()).blockReason());
        assertThrows(BusinessRuleException.class, () -> trash.restore(row.id()));
    }

    private DeletedItemDtos.Summary item(long sourceId) {
        return trash.list().items().stream().filter(i -> i.sourceId() == sourceId && i.type() != DeletedItemDtos.Type.PURCHASE_ORDER)
                .findFirst().orElseThrow();
    }
    private SalesOrder credit(SalesOrder invoice, String amount) {
        return sales.createCreditNote(invoice.id(), new SalesOrderService.CreditNoteRequest(CreditReason.OTHER, List.of(),
                List.of(new SalesOrderService.CreditAmount("Korting", new BigDecimal(amount))), false, null));
    }
    private SalesOrder issued() {
        var customer = customers.create(new Customer(null, "Trash credit " + UUID.randomUUID(), "Buyer", null, null,
                "BE0000000000", "BE", Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var created = sales.create(customer.id(), "BE", "DAP", DocumentType.FACTUUR);
        var stored = em.find(SalesOrderEntity.class, created.id());
        stored.extraLinesJson = "[{\"description\":\"Service\",\"quantity\":1,\"unitPriceEur\":100}]";
        stored.freightPricingStrategy = FreightPricingStrategy.FIXED;
        stored.manualFreightEur = BigDecimal.ZERO;
        stored.freight = FreightState.AANGEVULD;
        stored.status = QuoteStatus.UITGEREIKT;
        em.flush(); em.clear();
        return sales.get(created.id());
    }
}
