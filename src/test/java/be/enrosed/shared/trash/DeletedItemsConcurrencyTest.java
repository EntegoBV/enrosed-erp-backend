package be.enrosed.shared.trash;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.application.SalesOrderService;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.shared.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DeletedItemsConcurrencyTest {
    @Inject EntityManager entities;
    @Inject SalesOrderService sales;
    @Inject DeletedItemsService trash;

    @Test
    void simultaneousRestoreRequestsRestoreOneIdentityAndWriteOneAuditEvent() throws Exception {
        long orderId = QuarkusTransaction.requiringNew().call(() -> {
            var order = new SalesOrderEntity();
            order.number = "RECOVER-" + UUID.randomUUID(); order.docType = DocumentType.OFFERTE;
            entities.persist(order); entities.flush();
            return order.id;
        });
        try {
            QuarkusTransaction.requiringNew().run(() -> sales.delete(orderId));
            long id = trash.list().items().stream().filter(i -> i.sourceId() == orderId
                    && i.type() == DeletedItemDtos.Type.QUOTE).findFirst().orElseThrow().id();
            var start = new CountDownLatch(1);
            try (var threads = Executors.newFixedThreadPool(2)) {
                Callable<Boolean> restore = () -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { trash.restore(id); return true; }
                    catch (NotFoundException alreadyRestored) { return false; }
                };
                var first = threads.submit(restore); var second = threads.submit(restore); start.countDown();
                assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            }
            QuarkusTransaction.requiringNew().run(() -> {
                assertEquals(orderId, sales.get(orderId).id());
                assertEquals(1L, ((Number) entities.createNativeQuery("select count(*) from activity_log where entity_id=:id and entity_type='SALES_ORDER' and action='RESTORED'")
                        .setParameter("id", Long.toString(orderId)).getSingleResult()).longValue());
            });
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                entities.createNativeQuery("delete from deleted_item where source_kind='SALES' and source_id=:id").setParameter("id", orderId).executeUpdate();
                entities.createNativeQuery("delete from sales_order where id=:id").setParameter("id", orderId).executeUpdate();
                entities.createNativeQuery("delete from activity_log where entity_id=:id and entity_type='SALES_ORDER'").setParameter("id", Long.toString(orderId)).executeUpdate();
            });
        }
    }
}
