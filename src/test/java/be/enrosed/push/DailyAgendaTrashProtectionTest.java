package be.enrosed.push;

import be.enrosed.sales.adapter.out.persistence.SalesEntities.SalesOrderEntity;
import be.enrosed.sales.domain.DocumentType;
import be.enrosed.sales.domain.QuoteStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class DailyAgendaTrashProtectionTest {
    @Inject EntityManager entities;

    @Test @TestTransaction
    void digestUsesTheActualEntityAndOmitsTrashedOverdueInvoices() {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Brussels"));
        long before = ((Number) entities.createNativeQuery("select count(*) from sales_order"
                        + " where deleted_at is null and docType='FACTUUR' and status='VERZONDEN' and invoiceDueDate < :today")
                .setParameter("today", today).getSingleResult()).longValue();
        var active = invoice(today.minusDays(1));
        var retained = invoice(today.minusDays(1));
        entities.flush();
        entities.createNativeQuery("update sales_order set deleted_at=current_timestamp where id=:id")
                .setParameter("id", retained.id).executeUpdate();
        var phones = mock(WebPushNotifier.class); // No push or network delivery in this test.
        new DailyAgendaPush(phones, entities).morningDigest();
        verify(phones).notifyAll(eq("agenda"), anyString(),
                contains("! " + (before + 1) + " factuur/facturen vervallen"), eq("/"));
        verifyNoMoreInteractions(phones);
    }

    private SalesOrderEntity invoice(LocalDate dueDate) {
        var order = new SalesOrderEntity(); order.docType = DocumentType.FACTUUR;
        order.status = QuoteStatus.VERZONDEN; order.invoiceDueDate = dueDate;
        entities.persist(order); return order;
    }
}
