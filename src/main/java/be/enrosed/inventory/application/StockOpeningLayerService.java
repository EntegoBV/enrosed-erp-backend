package be.enrosed.inventory.application;

import be.enrosed.catalog.application.ProductService;
import be.enrosed.catalog.domain.Product;
import be.enrosed.inventory.adapter.out.persistence.InventoryStore;
import be.enrosed.inventory.adapter.out.persistence.StockOpeningLayerEntity;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.UnprocessableBusinessRuleException;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.CurrentActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Opening values: the documented acquisition value of stock from before the
 * containers in the ERP, each with a quantity, a date and its source. It is
 * not a free price; it is the oldest layer a closing can fill from.
 *
 * A row is never edited and never deleted. Replacing or removing one retires
 * it and, for a replacement, adds a new row, so a final closing that used the
 * old one can still name it. Nothing here computes a closing.
 */
@ApplicationScoped
public class StockOpeningLayerService {

    public static final int MAX_ROWS = 500;

    private final InventoryStore.OpeningLayers layers;
    private final CurrentActor actor;

    @Inject Instance<ProductService> products;

    public StockOpeningLayerService(InventoryStore.OpeningLayers layers, CurrentActor actor) {
        this.layers = layers;
        this.actor = actor;
    }

    public record Row(Long productId, Integer quantity, BigDecimal unitValueEur, String note) {}

    public record Write(LocalDate asOfDate, String source, List<Row> rows) {}

    /** The active opening values, newest first. */
    @Transactional
    public List<StockOpeningLayerEntity> active() {
        return layers.list("retiredAt is null order by createdAt desc, id desc");
    }

    @Transactional
    public List<StockOpeningLayerEntity> save(Write write) {
        if (write == null || write.source() == null || write.source().isBlank()) {
            throw new UnprocessableBusinessRuleException("Vermeld de bron van deze beginwaarde");
        }
        if (write.asOfDate() == null) throw new UnprocessableBusinessRuleException("Geef de datum van deze waarde");
        if (write.rows() == null || write.rows().isEmpty()) {
            throw new UnprocessableBusinessRuleException("Geef per product een aantal groter dan nul");
        }
        if (write.rows().size() > MAX_ROWS) {
            throw new UnprocessableBusinessRuleException("Bewaar hoogstens " + MAX_ROWS + " beginwaarden tegelijk");
        }
        for (Row row : write.rows()) {
            if (row == null || row.productId() == null || row.quantity() == null || row.quantity() <= 0) {
                throw new UnprocessableBusinessRuleException("Geef per product een aantal groter dan nul");
            }
            if (row.unitValueEur() == null || row.unitValueEur().signum() < 0) {
                throw new UnprocessableBusinessRuleException("Geef per product een waarde per stuk");
            }
        }
        Map<Long, Product> existing = products.get().list().stream().collect(Collectors.toMap(Product::id, Function.identity()));
        for (Row row : write.rows()) {
            if (!existing.containsKey(row.productId())) throw new NotFoundException("Product", row.productId());
        }

        ActorRef who = actor.current();
        Instant now = Instant.now();
        String source = cut(write.source().strip(), 500);
        List<StockOpeningLayerEntity> saved = new ArrayList<>();
        for (Row row : write.rows()) {
            Product product = existing.get(row.productId());
            /* One value per product and date: the earlier one is retired, not changed. */
            for (StockOpeningLayerEntity earlier : layers.list("productId = ?1 and asOfDate = ?2 and retiredAt is null",
                    row.productId(), write.asOfDate())) {
                earlier.retiredBy = who.username();
                earlier.retiredAt = now;
            }
            StockOpeningLayerEntity layer = new StockOpeningLayerEntity();
            layer.productId = product.id();
            layer.sku = cut(product.sku(), 120);
            layer.productName = cut(product.nameWithColour(), 255);
            layer.quantity = row.quantity();
            layer.unitValueEur = row.unitValueEur().setScale(4, RoundingMode.HALF_UP);
            layer.asOfDate = write.asOfDate();
            layer.source = source;
            layer.note = row.note() == null || row.note().isBlank() ? null : cut(row.note().strip(), 1000);
            layer.createdBy = who.username();
            layer.createdByName = cut(who.displayName(), 120);
            layer.createdAt = now;
            layers.persist(layer);
            saved.add(layer);
        }
        layers.flush();
        return saved;
    }

    /** Retires an opening value; the row stays. */
    @Transactional
    public void retire(long id) {
        StockOpeningLayerEntity layer = layers.findById(id);
        if (layer == null) throw new NotFoundException("Beginwaarde", id);
        if (layer.retiredAt != null) return;
        layer.retiredBy = actor.current().username();
        layer.retiredAt = Instant.now();
        layers.flush();
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
