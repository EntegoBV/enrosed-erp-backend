package be.enrosed.inventory.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every table of the year-end inventory is built from its entity on H2: each
 * entity is saved with a value in every column and read back from the database.
 */
@QuarkusTest
@TestTransaction
class InventoryStoreTest {

    @Inject EntityManager entityManager;
    @Inject InventoryStore.Rules rules;
    @Inject InventoryStore.Counts counts;
    @Inject InventoryStore.CountLines countLines;
    @Inject InventoryStore.OpeningLayers openingLayers;
    @Inject InventoryStore.Closings closings;
    @Inject InventoryStore.Decisions decisions;
    @Inject InventoryStore.Movements movements;
    @Inject InventoryStore.Containers containers;
    @Inject InventoryStore.Lots lots;
    @Inject InventoryStore.Articles articles;
    @Inject InventoryStore.Layers layers;
    @Inject InventoryStore.Lines lines;
    @Inject InventoryStore.Separates separates;
    @Inject InventoryStore.WriteDowns writeDowns;

    @Test
    void theValuationRuleReadsBack() throws Exception {
        assertReadsBack(rules, new StockValuationRuleEntity(), 9);
    }

    @Test
    void aCountSessionAndItsLineReadBack() throws Exception {
        assertReadsBack(counts, new StockCountEntity(), 17);
        assertReadsBack(countLines, new StockCountLineEntity(), 26);
    }

    @Test
    void anOpeningLayerReadsBack() throws Exception {
        assertReadsBack(openingLayers, new StockOpeningLayerEntity(), 14);
    }

    @Test
    void aClosingAndItsDecisionReadBack() throws Exception {
        assertReadsBack(closings, new StockClosingEntity(), 52);
        assertReadsBack(decisions, new StockClosingDecisionEntity(), 22);
    }

    @Test
    void theOutputRowsOfAClosingReadBack() throws Exception {
        assertReadsBack(movements, new StockClosingMovementEntity(), 26);
        assertReadsBack(containers, new StockClosingContainerEntity(), 56);
        assertReadsBack(lots, new StockClosingLotEntity(), 42);
        assertReadsBack(articles, new StockClosingArticleEntity(), 31);
        assertReadsBack(layers, new StockClosingLayerEntity(), 28);
        assertReadsBack(lines, new StockClosingLineEntity(), 30);
        assertReadsBack(separates, new StockClosingSeparateEntity(), 29);
        assertReadsBack(writeDowns, new StockClosingWriteDownEntity(), 16);
    }

    @Test
    void anEmptyRowReadsBackWithEveryColumnNull() throws Exception {
        StockClosingLotEntity lot = new StockClosingLotEntity();
        lots.persist(lot);
        entityManager.flush();
        entityManager.clear();

        StockClosingLotEntity stored = lots.findById(lot.id);
        for (Field field : columns(StockClosingLotEntity.class)) {
            if (!field.getName().equals("id")) {
                assertNull(field.get(stored), field.getName());
            }
        }
    }

    @Test
    void aUnitValueKeepsFourDecimals() {
        // Lot A of the first worked example: 2,5449 per piece, 930 pieces, 2.366,76.
        StockClosingLotEntity lot = new StockClosingLotEntity();
        lot.unitValueEur = new BigDecimal("2.5449");
        lot.lotCostEur = new BigDecimal("2366.76");
        lots.persist(lot);
        StockClosingLayerEntity layer = new StockClosingLayerEntity();
        layer.unitValueEur = new BigDecimal("2.5449");
        layer.unitEstimatedEur = new BigDecimal("0.2025");
        layer.valueEur = new BigDecimal("2366.76");
        layers.persist(layer);
        StockOpeningLayerEntity opening = new StockOpeningLayerEntity();
        opening.unitValueEur = new BigDecimal("2.5449");
        openingLayers.persist(opening);
        StockClosingArticleEntity article = new StockClosingArticleEntity();
        article.averageUnitEur = new BigDecimal("2.5053");
        articles.persist(article);
        StockClosingWriteDownEntity writeDown = new StockClosingWriteDownEntity();
        writeDown.layerUnitEur = new BigDecimal("2.5449");
        writeDown.marketUnitEur = new BigDecimal("0.5000");
        writeDown.amountEur = new BigDecimal("24.54");
        writeDowns.persist(writeDown);
        StockClosingContainerEntity container = new StockClosingContainerEntity();
        container.cnyToUsd = new BigDecimal("0.14000000");
        container.usdToEurGoods = new BigDecimal("0.92000000");
        containers.persist(container);
        entityManager.flush();
        entityManager.clear();

        assertEquals(new BigDecimal("2.5449"), lots.findById(lot.id).unitValueEur);
        assertEquals(new BigDecimal("2366.76"), lots.findById(lot.id).lotCostEur);
        assertEquals(new BigDecimal("2.5449"), layers.findById(layer.id).unitValueEur);
        assertEquals(new BigDecimal("0.2025"), layers.findById(layer.id).unitEstimatedEur);
        assertEquals(new BigDecimal("2366.76"), layers.findById(layer.id).valueEur);
        assertEquals(new BigDecimal("2.5449"), openingLayers.findById(opening.id).unitValueEur);
        assertEquals(new BigDecimal("2.5053"), articles.findById(article.id).averageUnitEur);
        assertEquals(new BigDecimal("2.5449"), writeDowns.findById(writeDown.id).layerUnitEur);
        assertEquals(new BigDecimal("0.5000"), writeDowns.findById(writeDown.id).marketUnitEur);
        assertEquals(new BigDecimal("24.54"), writeDowns.findById(writeDown.id).amountEur);
        assertEquals(new BigDecimal("0.14000000"), containers.findById(container.id).cnyToUsd);
        assertEquals(new BigDecimal("0.92000000"), containers.findById(container.id).usdToEurGoods);
    }

    /** Fills every column, saves, empties the persistence context and compares what the database returns. */
    private <T> void assertReadsBack(PanacheRepository<T> repository, T entity, int expectedColumns) throws Exception {
        List<Field> fields = columns(entity.getClass());
        assertEquals(expectedColumns, fields.size(), entity.getClass().getSimpleName());
        int ordinal = 0;
        for (Field field : fields) {
            ordinal++;
            if (!field.getName().equals("id")) {
                field.set(entity, sample(field, ordinal));
            }
        }
        repository.persist(entity);
        entityManager.flush();
        Long id = (Long) fields.get(0).get(entity);
        assertNotNull(id, "the database hands out the id");
        entityManager.clear();

        T stored = repository.findById(id);
        assertNotSame(entity, stored);
        for (Field field : fields) {
            assertEquals(field.get(entity), field.get(stored),
                    entity.getClass().getSimpleName() + "." + field.getName());
        }
    }

    /** The mapped fields in the order of the table; the build may have made them non-public. */
    private static List<Field> columns(Class<?> entity) {
        List<Field> fields = Arrays.stream(entity.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Column.class))
                .toList();
        fields.forEach(field -> field.setAccessible(true));
        assertEquals("id", fields.get(0).getName());
        return fields;
    }

    /** A value that differs per column and fills the column to its full length or scale. */
    private static Object sample(Field field, int ordinal) {
        Column column = field.getAnnotation(Column.class);
        Class<?> type = field.getType();
        if (type == Long.class) {
            return 1_000_000_000_000L + ordinal;
        }
        if (type == Integer.class) {
            return 1_000 + ordinal;
        }
        if (type == Boolean.class) {
            return ordinal % 2 == 0;
        }
        if (type == LocalDate.class) {
            return LocalDate.of(2026, 12, 31).minusDays(ordinal);
        }
        if (type == Instant.class) {
            return Instant.parse("2027-01-02T09:15:00Z").plusSeconds(ordinal);
        }
        if (type == BigDecimal.class) {
            assertEquals(19, column.precision(), field.getName());
            return new BigDecimal("1234.56789012").add(BigDecimal.valueOf(ordinal))
                    .setScale(column.scale(), java.math.RoundingMode.HALF_UP);
        }
        assertTrue(type == String.class, field.getName());
        if (!column.columnDefinition().isEmpty()) {
            return (field.getName() + " é€ ").repeat(2_000);
        }
        return (ordinal + field.getName() + "x".repeat(column.length())).substring(0, column.length());
    }
}
