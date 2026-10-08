package be.enrosed.inventory.application;

import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingContainerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLotEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingMovementEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What moved between a correction and the version it replaces.
 *
 * A new version is rebuilt from live data, so more can differ than the one
 * thing the user set out to correct. The comparison reads only the stored
 * rows of the two closings and lists every product, lot, movement and
 * opening value that is not the same on both sides. It cannot say why a
 * figure moved; the user reads the list before finalising.
 */
public final class ClosingVersionDiff {

    private ClosingVersionDiff() {}

    /** The stored rows of one version that the comparison reads. */
    public record Rows(StockClosingEntity closing, List<StockClosingArticleEntity> articles,
                       List<StockClosingLotEntity> lots, List<StockClosingContainerEntity> containers,
                       List<StockClosingMovementEntity> movements, List<StockClosingLayerEntity> layers) {}

    public record ArticleChange(long productId, String sku, String productName, Integer quantityBefore,
                                Integer quantityAfter, BigDecimal costValueBeforeEur, BigDecimal costValueAfterEur,
                                BigDecimal writeDownBeforeEur, BigDecimal writeDownAfterEur) {}

    public record LotChange(long purchaseOrderId, String displayName, long productId, String productName,
                            BigDecimal unitValueBeforeEur, BigDecimal unitValueAfterEur) {}

    /** The effect is what the line counted for: its effective delta when applied, zero when not, null when not listed. */
    public record MovementChange(long movementId, String productName, String locationName, String reference,
                                 Integer effectBefore, Integer effectAfter) {}

    public record OpeningChange(long openingLayerId, String productName, String source, Integer quantityBefore,
                                BigDecimal unitValueBeforeEur, Integer quantityAfter, BigDecimal unitValueAfterEur) {}

    /** Each list holds only what differs; "before" is the replaced version and null means absent on that side. */
    public record Changes(long againstClosingId, int againstVersionNo, BigDecimal totalBeforeEur, BigDecimal totalAfterEur,
                          List<ArticleChange> articles, List<LotChange> lots, List<MovementChange> movements,
                          List<OpeningChange> openingLayers) {

        public boolean isEmpty() {
            return articles.isEmpty() && lots.isEmpty() && movements.isEmpty() && openingLayers.isEmpty();
        }
    }

    public static Changes between(Rows current, Rows replaced) {
        return new Changes(replaced.closing().id == null ? 0 : replaced.closing().id,
                replaced.closing().versionNo == null ? 0 : replaced.closing().versionNo,
                replaced.closing().totalValueEur, current.closing().totalValueEur,
                articles(current, replaced), lots(current, replaced), movements(current, replaced), openings(current, replaced));
    }

    private static List<ArticleChange> articles(Rows current, Rows replaced) {
        Map<Long, StockClosingArticleEntity> before = new TreeMap<>();
        Map<Long, StockClosingArticleEntity> after = new TreeMap<>();
        replaced.articles().forEach(article -> before.put(article.productId, article));
        current.articles().forEach(article -> after.put(article.productId, article));
        List<ArticleChange> changes = new ArrayList<>();
        TreeSet<Long> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (Long id : ids) {
            StockClosingArticleEntity old = before.get(id), now = after.get(id);
            if (old != null && now != null && Objects.equals(old.closingQuantity, now.closingQuantity)
                    && same(old.costValueEur, now.costValueEur) && same(old.writeDownEur, now.writeDownEur)) continue;
            /* A product that lies nowhere and is worth nothing on the one side it is on did not move. */
            if ((old == null || now == null) && empty(old == null ? now : old)) continue;
            StockClosingArticleEntity named = now != null ? now : old;
            changes.add(new ArticleChange(id, named.sku, named.productName,
                    old == null ? null : old.closingQuantity, now == null ? null : now.closingQuantity,
                    old == null ? null : old.costValueEur, now == null ? null : now.costValueEur,
                    old == null ? null : old.writeDownEur, now == null ? null : now.writeDownEur));
        }
        return changes;
    }

    private static boolean empty(StockClosingArticleEntity article) {
        return (article.closingQuantity == null || article.closingQuantity == 0)
                && (article.costValueEur == null || article.costValueEur.signum() == 0)
                && (article.writeDownEur == null || article.writeDownEur.signum() == 0);
    }

    private record LotKey(long purchaseOrderId, long productId) implements Comparable<LotKey> {
        @Override
        public int compareTo(LotKey other) {
            int byOrder = Long.compare(purchaseOrderId, other.purchaseOrderId);
            return byOrder != 0 ? byOrder : Long.compare(productId, other.productId);
        }
    }

    /** The lots a version values itself; the ones recomputed for comparison with an earlier year are left out. */
    private static Map<LotKey, StockClosingLotEntity> valuedLots(Rows rows) {
        Map<LotKey, StockClosingLotEntity> lots = new TreeMap<>();
        for (StockClosingLotEntity lot : rows.lots()) {
            if ("VORIG".equals(lot.role) || lot.purchaseOrderId == null || lot.productId == null) continue;
            lots.put(new LotKey(lot.purchaseOrderId, lot.productId), lot);
        }
        return lots;
    }

    private static List<LotChange> lots(Rows current, Rows replaced) {
        Map<LotKey, StockClosingLotEntity> before = valuedLots(replaced);
        Map<LotKey, StockClosingLotEntity> after = valuedLots(current);
        Map<Long, String> names = new LinkedHashMap<>();
        replaced.containers().forEach(container -> names.put(container.purchaseOrderId, container.displayName));
        current.containers().forEach(container -> names.put(container.purchaseOrderId, container.displayName));
        List<LotChange> changes = new ArrayList<>();
        TreeSet<LotKey> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (LotKey key : keys) {
            StockClosingLotEntity old = before.get(key), now = after.get(key);
            if (old != null && now != null && same(old.unitValueEur, now.unitValueEur)) continue;
            StockClosingLotEntity named = now != null ? now : old;
            changes.add(new LotChange(key.purchaseOrderId(), names.get(key.purchaseOrderId()), key.productId(),
                    named.productName, old == null ? null : old.unitValueEur, now == null ? null : now.unitValueEur));
        }
        return changes;
    }

    private static Integer effect(StockClosingMovementEntity row) {
        if (row == null || Boolean.TRUE.equals(row.removed)) return null;
        return Boolean.TRUE.equals(row.applied) && row.effectiveDelta != null ? row.effectiveDelta : 0;
    }

    private static List<MovementChange> movements(Rows current, Rows replaced) {
        Map<Long, StockClosingMovementEntity> before = new TreeMap<>();
        Map<Long, StockClosingMovementEntity> after = new TreeMap<>();
        replaced.movements().forEach(row -> before.put(row.movementId, row));
        current.movements().forEach(row -> after.put(row.movementId, row));
        List<MovementChange> changes = new ArrayList<>();
        TreeSet<Long> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (Long id : ids) {
            StockClosingMovementEntity old = before.get(id), now = after.get(id);
            Integer effectBefore = effect(old), effectAfter = effect(now);
            if (Objects.equals(effectBefore, effectAfter)) continue;
            /* A line that is new in the list and counts for nothing changed no figure. */
            if (old == null && effectAfter != null && effectAfter == 0) continue;
            StockClosingMovementEntity named = now != null ? now : old;
            changes.add(new MovementChange(id, named.productName, named.locationName, named.refText, effectBefore, effectAfter));
        }
        return changes;
    }

    private record Used(String source, int quantity, BigDecimal unitValueEur, Long productId) {}

    private static Map<Long, Used> openingsUsed(Rows rows) {
        Map<Long, Used> used = new TreeMap<>();
        for (StockClosingLayerEntity layer : rows.layers()) {
            if (!FifoValuer.SOURCE_OPENING.equals(layer.source) || layer.openingLayerId == null) continue;
            int quantity = layer.quantity == null ? 0 : layer.quantity;
            used.merge(layer.openingLayerId, new Used(layer.openingSource, quantity, layer.unitValueEur, layer.productId),
                    (left, right) -> new Used(left.source(), left.quantity() + right.quantity(), left.unitValueEur(), left.productId()));
        }
        return used;
    }

    private static List<OpeningChange> openings(Rows current, Rows replaced) {
        Map<Long, Used> before = openingsUsed(replaced);
        Map<Long, Used> after = openingsUsed(current);
        Map<Long, String> names = new LinkedHashMap<>();
        replaced.articles().forEach(article -> names.put(article.productId, article.productName));
        current.articles().forEach(article -> names.put(article.productId, article.productName));
        List<OpeningChange> changes = new ArrayList<>();
        TreeSet<Long> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (Long id : ids) {
            Used old = before.get(id), now = after.get(id);
            if (old != null && now != null && old.quantity() == now.quantity() && same(old.unitValueEur(), now.unitValueEur())) continue;
            Used named = now != null ? now : old;
            changes.add(new OpeningChange(id, names.get(named.productId()), named.source(),
                    old == null ? null : old.quantity(), old == null ? null : old.unitValueEur(),
                    now == null ? null : now.quantity(), now == null ? null : now.unitValueEur()));
        }
        return changes;
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
}
