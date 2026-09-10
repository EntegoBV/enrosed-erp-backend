package be.enrosed.media;

import be.enrosed.shared.NotFoundException;
import jakarta.persistence.EntityManager;

/** Fresh database checks: an already managed parent must not bypass its trash marker. */
final class MediaOrderAccess {
    private MediaOrderAccess() {}

    static boolean purchaseVisible(EntityManager entities, long id) {
        return visible(entities, "purchase_order", id, false);
    }

    static void requirePurchase(EntityManager entities, long id, boolean lock) {
        if (!visible(entities, "purchase_order", id, lock)) throw new NotFoundException("Inkooporder", id);
    }

    static void lockSales(EntityManager entities, long id) {
        if (!visible(entities, "sales_order", id, true)) throw new NotFoundException("Verkoopdocument", id);
    }

    private static boolean visible(EntityManager entities, String table, long id, boolean lock) {
        return !entities.createNativeQuery("select id from " + table + " where id = :id and deleted_at is null"
                        + (lock ? " for update" : ""))
                .setParameter("id", id).getResultList().isEmpty();
    }
}
