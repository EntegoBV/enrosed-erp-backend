package be.enrosed.shared.trash;

import be.enrosed.sales.adapter.out.persistence.SalesEntities;
import be.enrosed.sales.application.*;
import be.enrosed.sales.application.port.out.SalesRepositories;
import be.enrosed.sales.domain.*;
import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.Language;
import be.enrosed.shared.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static io.restassured.RestAssured.given;

@QuarkusTest
class DeletedItemsTest {
    @Inject DeletedItemsService trash;
    @Inject SalesOrderService sales;
    @Inject QuoteService quotes;
    @Inject CustomerService customers;
    @Inject SalesRepositories.Orders orders;
    @Inject SalesRepositories.Events events;
    @Inject SalesRepositories.Revisions revisions;
    @Inject EntityManager em;
    @Inject ObjectMapper json;

    @Test @TestTransaction @TestSecurity(user = "emre", roles = "admin")
    void quoteRoundTripPreservesIdentityHistoryRevisionsNotesAndHidesEveryActivePath() throws Exception {
        var created = fixture(DocumentType.OFFERTE);
        var entity = em.find(SalesEntities.SalesOrderEntity.class, created.id());
        entity.portalToken = UUID.randomUUID().toString(); entity.status = QuoteStatus.VERZONDEN;
        entity.sentAt = Instant.now(); entity.notes = "Klanttekst <b>letterlijk</b>";
        entity.internalNotes = "Interne toelichting"; entity.customerMessage = "Graag volgende week leveren";
        em.flush(); em.clear();
        var original = sales.get(created.id());
        var revision = revisions.save(new QuoteRevision(null, original.id(), RevisionStatus.IN_AFWACHTING, Instant.now(),
                "Klant", "Graag bellen", null, null, null, List.of()));
        var history = events.add(new QuoteEvent(null, original.id(), QuoteEvent.Type.VERSTUURD, Instant.now(), "Emre", false, "Verstuurd", null));
        sales.delete(original.id());
        assertThrows(NotFoundException.class, () -> sales.get(original.id()));
        assertTrue(orders.findByPortalToken(original.portalToken()).isEmpty());
        assertFalse(sales.list().stream().anyMatch(o -> o.id().equals(original.id())));
        assertFalse(revisions.findPending().stream().anyMatch(r -> r.id().equals(revision.id())));
        assertThrows(NotFoundException.class, () -> quotes.history(original.id()));
        assertThrows(NotFoundException.class, () -> quotes.revisionsFor(original.id()));
        assertNotNull(em.find(SalesEntities.QuoteRevisionEntity.class, revision.id()));
        assertNotNull(em.find(SalesEntities.QuoteEventEntity.class, history.id()));
        var row = item(original.id());
        assertEquals(90, trash.list().retentionDays());
        assertEquals(90 * 86400, row.expiresAt().getEpochSecond() - row.deletedAt().getEpochSecond());
        var detail = trash.detail(row.id());
        assertEquals("Klanttekst <b>letterlijk</b>", detail.notes());
        assertTrue(detail.fields().stream().anyMatch(f -> f.value().equals("Graag volgende week leveren")));
        assertTrue(detail.restoreAllowed());
        String wire = json.writeValueAsString(detail);
        assertFalse(wire.contains(original.portalToken()));
        assertFalse(wire.contains("orderJson"));
        assertFalse(wire.contains("storageKey"));
        var restored = trash.restore(row.id());
        assertEquals(original.id(), restored.sourceId());
        assertEquals("/sales/" + original.id(), restored.targetRoute());
        assertEquals(original, sales.get(original.id()));
        assertTrue(quotes.history(original.id()).stream().anyMatch(e -> e.id().equals(history.id())));
        assertTrue(quotes.revisionsFor(original.id()).stream().anyMatch(r -> r.id().equals(revision.id())));
        assertThrows(NotFoundException.class, () -> trash.detail(row.id()));
        assertThrows(NotFoundException.class, () -> trash.restore(row.id()));
        assertEquals(1L, ((Number) em.createNativeQuery("select count(*) from activity_log where entity_id = :id and action = 'RESTORED'")
                .setParameter("id", Long.toString(original.id())).getSingleResult()).longValue());
    }

    @Test @TestTransaction
    void deletedNumbersRemainReservedAndOldPayloadCannotCreateADuplicate() {
        var first = fixture(DocumentType.FACTUUR);
        sales.delete(first.id());
        var second = sales.create(first.customerId(), "BE", "DAP", DocumentType.FACTUUR);
        assertNotEquals(first.number(), second.number());
        assertTrue(orders.numbersIncludingDeleted().stream().anyMatch(n -> n.id() == first.id() && n.number().equals(first.number())));
        assertThrows(NotFoundException.class, () -> orders.save(first));
        var row = item(first.id());
        trash.restore(row.id());
        assertEquals(first.number(), sales.get(first.id()).number());
        assertEquals(QuoteStatus.CONCEPT, sales.get(first.id()).status());
        assertNull(sales.get(first.id()).sentAt());
        assertNull(sales.get(first.id()).goodsShippedAt());
    }

    @Test @TestTransaction
    void expiryClosesListDetailAndRestoreWithoutDestroyingTheDocument() {
        var order = fixture(DocumentType.OFFERTE);
        sales.delete(order.id());
        var row = item(order.id());
        em.find(DeletedItemEntity.class, row.id()).expiresAt = Instant.now().minusSeconds(1);
        em.flush(); em.clear();
        assertFalse(trash.list().items().stream().anyMatch(i -> i.id() == row.id()));
        assertThrows(NotFoundException.class, () -> trash.detail(row.id()));
        assertThrows(NotFoundException.class, () -> trash.restore(row.id()));
        assertEquals(1, em.createNativeQuery("select id from sales_order where id = :id and deleted_at is not null").setParameter("id", order.id()).getResultList().size());
    }

    @Test @TestTransaction
    void restoringAnInvoiceRequiresItsSourceAndPreventsTwoInvoicesFromOneQuote() {
        var source = fixture(DocumentType.OFFERTE);
        var invoice = sales.createInvoiceFrom(source.id());
        sales.delete(invoice.id());
        var invoiceTrash = item(invoice.id());
        sales.delete(source.id());
        var sourceTrash = item(source.id());
        assertFalse(trash.detail(invoiceTrash.id()).restoreAllowed());
        assertTrue(trash.detail(invoiceTrash.id()).blockReason().contains("oorspronkelijke offerte"));
        trash.restore(sourceTrash.id());
        var replacement = sales.createInvoiceFrom(source.id());
        assertNotEquals(invoice.id(), replacement.id());
        assertTrue(trash.detail(invoiceTrash.id()).blockReason().contains("andere factuur"));
        assertThrows(BusinessRuleException.class, () -> trash.restore(invoiceTrash.id()));
        assertThrows(NotFoundException.class, () -> sales.get(invoice.id()));
    }

    @Test @TestTransaction
    void archiveIsIndependentAndCustomerDependencyRemainsProtected() {
        var order = fixture(DocumentType.OFFERTE);
        sales.archive(order.id());
        em.flush(); em.clear();
        var archived = sales.get(order.id());
        assertNotNull(archived.archivedAt());
        sales.delete(order.id());
        assertThrows(BusinessRuleException.class, () -> customers.delete(order.customerId()));
        trash.restore(item(order.id()).id());
        assertEquals(archived.archivedAt(), sales.get(order.id()).archivedAt());
    }

    @Test void anonymousCannotBrowseTrash() { given().get("/api/deleted-items").then().statusCode(401); }
    @Test @TestSecurity(user = "viewer", roles = "viewer")
    void nonAdminCannotBrowseReadOrRestoreTrash() {
        given().get("/api/deleted-items").then().statusCode(403);
        given().get("/api/deleted-items/1").then().statusCode(403);
        given().post("/api/deleted-items/1/restore").then().statusCode(403);
        given().get("/api/deleted-items/1/attachments/1/file").then().statusCode(403);
    }

    private DeletedItemDtos.Summary item(long sourceId) {
        return trash.list().items().stream().filter(i -> i.sourceId() == sourceId && i.type() != DeletedItemDtos.Type.PURCHASE_ORDER).findFirst().orElseThrow();
    }
    private SalesOrder fixture(DocumentType type) {
        var customer = customers.create(new Customer(null, "Trash QA " + UUID.randomUUID(), "Buyer", "test@example.invalid", null,
                "BE0000000000", "BE", Language.NL, "Test 1", "2000", "Antwerpen", "DAP", null, null, LocalDate.now()));
        var order = sales.create(customer.id(), "BE", "DAP", type);
        return orders.save(order.withExtraLines(List.of(new SalesExtraLine("Voorbeeld", BigDecimal.ONE, new BigDecimal("100.00")))));
    }
}
