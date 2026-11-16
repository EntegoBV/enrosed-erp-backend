package be.enrosed.sourcing.tracking;

import be.enrosed.shared.audit.ActivityChangeDto;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.sourcing.adapter.out.persistence.SourcingEntities.PurchaseOrderEntity;
import be.enrosed.sourcing.application.port.out.SourcingRepositories;
import be.enrosed.sourcing.domain.PurchaseOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ClientErrorException;
import java.time.Instant;
import java.util.*;
import static be.enrosed.sourcing.tracking.ContainerTracking.hash;

@ApplicationScoped
public class VesselLinkStore {
    private static final String ORDER = "be.enrosed.sourcing.adapter.out.persistence.SourcingEntities$PurchaseOrderEntity";
    @Inject SourcingRepositories.PurchaseOrders orders;
    @Inject EntityManager entities;
    @Inject ActivityLogService activity;
    public record Link(String referenceHash, String imo, Instant linkedAt) {}
    /**
     * The ship and voyage the final bill of lading named when it was taken over (purchase_order.bill_vessel,
     * bill_voyage); both null when none was. The purchase domain record does not carry them.
     */
    public record BillShip(String vessel, String voyage) {
        public static final BillShip NONE = new BillShip(null, null);

        /** A bill's ship and voyage as the two columns hold them: trimmed, nothing for blank, cut to the column. */
        public static BillShip of(String vessel, String voyage) {
            return new BillShip(column(vessel, 80), column(voyage, 40));
        }

        private static String column(String value, int length) {
            if (value == null || value.isBlank()) return null;
            String text = value.strip();
            return text.length() > length ? text.substring(0, length).strip() : text;
        }
    }

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
        if (imo == null) {
            if (link != null) link.delete();
            forgetArrival(order, "Schip losgekoppeld");
            return;
        }
        String binding = binding(order.trackingReference());
        if (link != null && Objects.equals(link.referenceHash, binding) && Objects.equals(link.imo, imo)) return;
        boolean fresh = link == null;
        if (!fresh && !Objects.equals(link.imo, imo)) forgetArrival(order, "Ander schip gekoppeld");
        if (fresh) { link = new VesselLinkEntity(); link.orderId = orderId; }
        link.referenceHash = binding; link.imo = imo; link.linkedAt = Instant.now();
        if (fresh) link.persist();
    }

    /**
     * The linked ship is another one now, or none: the arrival of the ship before is not this container's
     * ({@link TrackingDateSync#forgetPortArrival}). The bill columns stay: the bill still names its ship. The
     * entity is fetched here, after the order was locked: findByIdForUpdate detaches the instance read before it.
     */
    private void forgetArrival(PurchaseOrder order, String summary) {
        PurchaseOrderEntity entity = entities.find(PurchaseOrderEntity.class, order.id());
        if (entity == null) return;
        List<ActivityChangeDto> changes = TrackingDateSync.forgetPortArrival(entity);
        /* The expected arrival may have gone back with it: the order's history says why. */
        if (!changes.isEmpty())
            activity.record(ActivityLogService.ACTION_UPDATED, ActivityLogService.ENTITY_PURCHASE_ORDER,
                    Long.toString(order.id()), order.number(), summary, changes);
    }

    /**
     * The reference text of an order was rewritten. The linked ship stays with the order unless the text now
     * names another shipment (both texts recognised and no number in common): "BL:" put in front of a number or
     * a B/L added behind a container number must not unlink the ship. Called inside the order's save.
     *
     * <p>What the bill of lading named and the day the ship came in belong to the shipment the text named: they
     * go when the text is emptied or names another shipment, also when no ship is linked. The order's save has
     * written its values by then, so the caller reads the order again when it answers with it; the bill-of-lading
     * apply writes its bill columns after it changed the reference, so its own values stand.</p>
     */
    @Transactional
    public void rebind(long orderId, String before, String after) {
        boolean emptied = after == null || after.isBlank();
        Set<String> old = numbers(before), now = numbers(after);
        boolean otherShipment = !old.isEmpty() && !now.isEmpty() && Collections.disjoint(old, now);
        if (emptied || otherShipment) {
            PurchaseOrderEntity order = entities.find(PurchaseOrderEntity.class, orderId);
            if (order != null) {
                TrackingDateSync.forgetPortArrival(order);
                order.billVessel = null;
                order.billVoyage = null;
            }
        }
        if (emptied || Objects.equals(binding(before), binding(after))) return;
        VesselLinkEntity link = VesselLinkEntity.findById(orderId);
        if (link == null || !Objects.equals(link.referenceHash, binding(before))) return;
        if (otherShipment) return;
        String bound = link.referenceHash;
        link.referenceHash = binding(after);
        /* The stored port calls of this order hang on a key that holds the reference hash: they follow the link,
           so the row does not read "nog niet gecontroleerd" until the next scheduled run. */
        TrackingEntity kept = TrackingEntity.findById(ContainerTrackingService.vesselKey(orderId, bound, link.imo, link.linkedAt));
        String key = ContainerTrackingService.vesselKey(orderId, link.referenceHash, link.imo, link.linkedAt);
        if (kept == null || TrackingEntity.findById(key) != null) return;
        TrackingEntity moved = new TrackingEntity();
        moved.id = key; moved.trackingNumber = kept.trackingNumber; moved.shipmentId = kept.shipmentId;
        moved.observationJson = kept.observationJson; moved.fingerprint = kept.fingerprint; moved.checkedAt = kept.checkedAt;
        moved.updatedAt = kept.updatedAt; moved.changedAt = kept.changedAt; moved.nextCheckAt = kept.nextCheckAt;
        moved.lastError = kept.lastError; moved.terminal = kept.terminal; moved.etag = kept.etag; moved.lastModified = kept.lastModified;
        moved.persist();
        kept.delete();
    }

    /** Every number a recognised reference names; empty when the text is not recognised. */
    private static Set<String> numbers(String raw) {
        ContainerTracking.Recognised seen = ContainerTracking.recognise(raw);
        Set<String> found = new HashSet<>();
        if (List.of("CONTAINER", "BL", "BOOKING").contains(seen.kind()))
            for (String number : new String[] { seen.number(), seen.container(), seen.evergreenBl() }) if (number != null) found.add(number);
        return found;
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

    /** The bill's ship of every order that has one: one read for the whole tracking feed. */
    @Transactional
    public Map<Long, BillShip> billShips() {
        Map<Long, BillShip> ships = new HashMap<>();
        List<Object[]> rows = entities.createQuery("select o.id, o.billVessel, o.billVoyage from " + ORDER
                + " o where o.billVessel is not null or o.billVoyage is not null", Object[].class).getResultList();
        for (Object[] row : rows) ships.put((Long) row[0], new BillShip((String) row[1], (String) row[2]));
        return ships;
    }

    /** The bill's ship of one order; {@link BillShip#NONE} without one. */
    @Transactional
    public BillShip billShip(long orderId) {
        return entities.createQuery("select o.billVessel, o.billVoyage from " + ORDER + " o where o.id = :id", Object[].class)
                .setParameter("id", orderId).getResultStream().findFirst()
                .map(row -> new BillShip((String) row[0], (String) row[1])).orElse(BillShip.NONE);
    }

    /**
     * What taking over a bill of lading stores (BillOfLadingService.apply, the only writer): the ship and voyage
     * of a final bill, or {@link BillShip#NONE} for a draft. Set on the order as it is managed now and flushed at
     * once: locking the order (findByIdForUpdate, also inside {@link #save}) detaches that instance and loads a
     * new one, so a value that only stood in memory would be lost without an error.
     */
    @Transactional
    public void saveBillShip(long orderId, BillShip ship) {
        PurchaseOrderEntity order = entities.find(PurchaseOrderEntity.class, orderId);
        if (order == null) throw new NotFoundException();
        BillShip wanted = ship == null ? BillShip.NONE : BillShip.of(ship.vessel(), ship.voyage());
        order.billVessel = wanted.vessel();
        order.billVoyage = wanted.voyage();
        entities.flush();
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
