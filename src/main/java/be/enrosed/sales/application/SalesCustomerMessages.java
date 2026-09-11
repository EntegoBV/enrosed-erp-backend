package be.enrosed.sales.application;
import be.enrosed.sales.adapter.out.persistence.*;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.BusinessRuleException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Objects;

/** Customer-authored website messages remain distinct from staff document notes and channels. */
@ApplicationScoped
public class SalesCustomerMessages {
    public record Message(boolean readonly, String text) {}
    @Inject EntityManager entities;
    public Message find(SalesOrder order) {
        var stored = order.id() == null ? null : entities.find(SalesCustomerMessageEntity.class, order.id());
        return stored != null ? new Message(true, stored.message)
                : website(order) ? new Message(true, order.notes()) : new Message(false, null);
    }
    @Transactional(Transactional.TxType.MANDATORY)
    public void capture(SalesOrder order) {
        if (order.id() == null || !website(order) || entities.find(SalesCustomerMessageEntity.class, order.id()) != null) return;
        save(order.id(), order.notes());
    }
    void requireUpdate(SalesOrder before, SalesOrder after) {
        // The public intake creates and fills its empty draft in one transaction, then explicitly captures it.
        boolean unsubmitted = before.lines().isEmpty() && before.internalNotes() == null
                && (before.notes() == null || before.notes().isBlank());
        if (!unsubmitted) capture(before);
        var stored = entities.find(SalesCustomerMessageEntity.class, before.id());
        if (stored != null && !Objects.equals(Objects.toString(before.notes(), ""), Objects.toString(after.notes(), "")))
            throw new BusinessRuleException("Het bericht van de klant is alleen-lezen; voeg eigen uitleg toe bij de interne notities of voorwaarden");
    }
    void copy(SalesOrder source, SalesOrder target) {
        Message message = find(source);
        if (message.readonly() && entities.find(SalesCustomerMessageEntity.class, target.id()) == null) save(target.id(), message.text());
    }
    private void save(long id, String message) {
        var entity = new SalesCustomerMessageEntity(); entity.salesOrderId = id;
        entity.order = entities.getReference(SalesEntities.SalesOrderEntity.class, id);
        entity.message = message; entity.capturedAt = Instant.now(); entities.persist(entity);
    }
    static boolean website(SalesOrder order) {
        return "WEBSITE".equalsIgnoreCase(order.salesChannel()) || order.internalNotes() != null
                && order.internalNotes().stripLeading().startsWith(SalesOrderService.WEBSITE_REQUEST_MARKER);
    }
}
