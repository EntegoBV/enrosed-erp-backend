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
    /** {@code pricesShown}: whether the website showed the customer prices when they sent the request; null when not known. */
    public record Message(boolean readonly, String text, Boolean pricesShown) {
        public Message(boolean readonly, String text) { this(readonly, text, null); }
    }
    @Inject EntityManager entities;
    public Message find(SalesOrder order) {
        var stored = order.id() == null ? null : entities.find(SalesCustomerMessageEntity.class, order.id());
        return stored != null ? new Message(true, stored.message, stored.pricesShown)
                : website(order) ? new Message(true, order.notes()) : new Message(false, null);
    }
    @Transactional(Transactional.TxType.MANDATORY)
    public void capture(SalesOrder order) {
        capture(order, null);
    }
    /**
     * At the website intake: also whether the website showed the customer prices (null: not known). A website
     * order's own customer save already stored its message (replace), so an existing row only gets what it lacks.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void capture(SalesOrder order, Boolean pricesShown) {
        if (order.id() == null || !website(order)) return;
        var stored = entities.find(SalesCustomerMessageEntity.class, order.id());
        if (stored == null) save(order.id(), order.notes(), pricesShown);
        else if (stored.pricesShown == null && pricesShown != null) stored.pricesShown = pricesShown;
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
    /** A customer who changes their own website order replaces their remark with it. */
    void replace(SalesOrder saved) {
        if (saved.id() == null) return;
        var stored = entities.find(SalesCustomerMessageEntity.class, saved.id());
        if (stored == null) { save(saved.id(), saved.notes(), null); return; }
        stored.message = saved.notes(); stored.capturedAt = Instant.now();
    }
    void copy(SalesOrder source, SalesOrder target) {
        Message message = find(source);
        if (message.readonly() && entities.find(SalesCustomerMessageEntity.class, target.id()) == null)
            save(target.id(), message.text(), message.pricesShown());
    }
    private void save(long id, String message, Boolean pricesShown) {
        var entity = new SalesCustomerMessageEntity(); entity.salesOrderId = id;
        entity.order = entities.getReference(SalesEntities.SalesOrderEntity.class, id);
        entity.message = message; entity.capturedAt = Instant.now(); entity.pricesShown = pricesShown;
        entities.persist(entity);
    }
    static boolean website(SalesOrder order) {
        return "WEBSITE".equalsIgnoreCase(order.salesChannel()) || order.internalNotes() != null
                && order.internalNotes().stripLeading().startsWith(SalesOrderService.WEBSITE_REQUEST_MARKER);
    }
}
