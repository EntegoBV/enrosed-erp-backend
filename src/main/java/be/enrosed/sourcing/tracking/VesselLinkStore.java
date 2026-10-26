package be.enrosed.sourcing.tracking;

import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ClientErrorException;
import java.time.Instant;
import java.util.*;
import static be.enrosed.sourcing.tracking.ContainerTracking.hash;

@ApplicationScoped
public class VesselLinkStore {
    @Inject SourcingRepositories.PurchaseOrders orders;
    public record Link(String referenceHash, String imo, Instant linkedAt) {}

    @Transactional
    public void save(long orderId, String imo) {
        save(orderId, imo, null);
    }

    @Transactional
    public void save(long orderId, String imo, String expectedReference) {
        PurchaseOrder order = orders.findByIdForUpdate(orderId).orElseThrow(NotFoundException::new);
        if (order.archivedAt() != null || order.trackingReference() == null || order.trackingReference().isBlank())
            throw new NotFoundException("Container met tracking niet gevonden.");
        if (expectedReference != null && !Objects.equals(expectedReference, order.trackingReference()))
            throw new ClientErrorException("De trackingreferentie is intussen gewijzigd. Open de container opnieuw voordat je een schip koppelt.", 409);
        if (imo != null && !validImo(imo))
            throw new BadRequestException("Gebruik het IMO-nummer van het schip: zeven cijfers met een geldig controlecijfer.");
        VesselLinkEntity link = VesselLinkEntity.findById(orderId);
        if (imo == null) { if (link != null) link.delete(); return; }
        String binding = binding(order.trackingReference());
        if (link != null && Objects.equals(link.referenceHash, binding) && Objects.equals(link.imo, imo)) return;
        boolean fresh = link == null;
        if (fresh) { link = new VesselLinkEntity(); link.orderId = orderId; }
        link.referenceHash = binding; link.imo = imo; link.linkedAt = Instant.now();
        if (fresh) link.persist();
    }

    @Transactional
    public Link find(PurchaseOrder order) {
        VesselLinkEntity entity = VesselLinkEntity.findById(order.id());
        return matching(order, entity == null ? null : new Link(entity.referenceHash, entity.imo, entity.linkedAt));
    }

    @Transactional
    public Map<Long, Link> all() {
        Map<Long, Link> links = new HashMap<>();
        for (VesselLinkEntity entity : VesselLinkEntity.<VesselLinkEntity>listAll())
            links.put(entity.orderId, new Link(entity.referenceHash, entity.imo, entity.linkedAt));
        return links;
    }

    static Link matching(PurchaseOrder order, Link link) {
        return link != null && Objects.equals(link.referenceHash(), binding(order.trackingReference())) ? link : null;
    }
    static String binding(String raw) { return hash(raw == null ? "" : raw.strip()); }
    public static boolean validImo(String value) {
        if (value == null || !value.matches("[0-9]{7}") || "0000000".equals(value)) return false;
        int sum = 0;
        for (int i = 0; i < 6; i++) sum += (value.charAt(i) - '0') * (7 - i);
        return sum % 10 == value.charAt(6) - '0';
    }
}
