package be.enrosed.inventory.application;

import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingContainerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingDecisionEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLineEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLotEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingMovementEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingSeparateEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingWriteDownEntity;
import jakarta.persistence.Column;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * The fingerprint of everything a closing freezes: figures, inputs, notices
 * and the rule. "Definitief maken" sends the fingerprint of the figures on
 * the screen and is refused when a recompute gives another one, so nobody
 * freezes figures they have not seen.
 *
 * It is the SHA-256 of a canonical text: one line per stored row, every
 * column in the order of the table, the rows in a fixed order. When and by
 * whom the closing was computed is not in it.
 */
public final class ClosingHash {

    private static final Comparator<Long> NULL_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<String> TEXT = Comparator.nullsFirst(Comparator.naturalOrder());

    private ClosingHash() {}

    /** The stored rows of one closing, in any order. */
    public record Rows(StockClosingEntity closing, List<StockClosingLineEntity> lines, List<StockClosingLayerEntity> layers,
                       List<StockClosingLotEntity> lots, List<StockClosingContainerEntity> containers,
                       List<StockClosingArticleEntity> articles, List<StockClosingSeparateEntity> separates,
                       List<StockClosingWriteDownEntity> writeDowns, List<StockClosingMovementEntity> movements,
                       List<StockClosingDecisionEntity> decisions, List<ClosingNotices.Notice> notices) {}

    public static String sha256(Rows rows) {
        return sha256(canonical(rows));
    }

    /** The text the fingerprint is taken of: lines joined with a line feed, each starting with its record name. */
    public static String canonical(Rows rows) {
        StockClosingEntity closing = rows.closing();
        List<String> lines = new ArrayList<>();
        lines.add(line("HEAD", List.of(encode(closing.closingYear, 0), encode(closing.versionNo, 0), encode(closing.closingDate, 0),
                encode(closing.cutoffAt, 0), encode(closing.previousClosingId, 0), encode(closing.supersedesId, 0),
                encode(closing.ruleMethod, 0), encode(closing.ruleVersion, 0), encode(closing.ruleEffectiveFromYear, 0),
                sha256(closing.ruleText == null ? "" : closing.ruleText))));
        table(lines, "LINE", rows.lines(), Set.of(), Comparator.comparing((StockClosingLineEntity row) -> row.productId, NULL_FIRST)
                .thenComparing(row -> row.locationId, NULL_FIRST));
        table(lines, "LAYER", rows.layers(), Set.of("lotId"), Comparator.comparing((StockClosingLayerEntity row) -> row.productId, NULL_FIRST)
                .thenComparing(row -> row.block, TEXT).thenComparing(row -> row.position, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(row -> row.salesOrderId, NULL_FIRST));
        table(lines, "LOT", rows.lots(), Set.of("containerId"), Comparator.comparing((StockClosingLotEntity row) -> row.purchaseOrderId, NULL_FIRST)
                .thenComparing(row -> row.role, TEXT).thenComparing(row -> row.productId, NULL_FIRST));
        table(lines, "CONTAINER", rows.containers(), Set.of(), Comparator.comparing((StockClosingContainerEntity row) -> row.purchaseOrderId, NULL_FIRST)
                .thenComparing(row -> row.role, TEXT));
        table(lines, "ARTICLE", rows.articles(), Set.of(), Comparator.comparing((StockClosingArticleEntity row) -> row.productId, NULL_FIRST));
        table(lines, "SEPARATE", rows.separates(), Set.of(), Comparator.comparing((StockClosingSeparateEntity row) -> row.kind, TEXT)
                .thenComparing(row -> row.purchaseOrderId, NULL_FIRST).thenComparing(row -> row.salesOrderId, NULL_FIRST)
                .thenComparing(row -> row.productId, NULL_FIRST).thenComparing(row -> row.decisionId, NULL_FIRST));
        table(lines, "WRITE_DOWN", rows.writeDowns(), Set.of(), Comparator.comparing((StockClosingWriteDownEntity row) -> row.decisionId, NULL_FIRST)
                .thenComparing(row -> row.layerPosition, Comparator.nullsFirst(Comparator.naturalOrder())));
        table(lines, "MOVEMENT", rows.movements(), Set.of(), Comparator.comparing((StockClosingMovementEntity row) -> row.movementId, NULL_FIRST));
        table(lines, "DECISION", rows.decisions(), Set.of(), Comparator.comparing((StockClosingDecisionEntity row) -> row.id, NULL_FIRST));
        rows.notices().stream()
                .sorted(Comparator.comparing(ClosingNotices.Notice::code, TEXT).thenComparing(ClosingNotices.Notice::message, TEXT))
                .forEach(notice -> lines.add(line("NOTICE", List.of(encode(notice.code(), 0), encode(notice.severity(), 0),
                        encode(notice.segment(), 0), encode(notice.message(), 0)))));
        lines.add(line("TOTALS", List.of(encode(closing.costValueEur, 2), encode(closing.writeDownEur, 2),
                encode(closing.ownValueEur, 2), encode(closing.demoValueEur, 2), encode(closing.partnerIncludedEur, 2),
                encode(closing.partnerExcludedEur, 2), encode(closing.transitIncludedEur, 2), encode(closing.transitExcludedEur, 2),
                encode(closing.invoicedOutEur, 2), encode(closing.totalValueEur, 2), encode(closing.estimatedEur, 2),
                encode(closing.ownQuantity, 0), encode(closing.unvaluedQuantity, 0))));
        return String.join("\n", lines);
    }

    /** One line per row: every column of the table in its own order, without the ids that change at each rebuild. */
    private static <T> void table(List<String> lines, String name, List<T> rows, Set<String> skipped, Comparator<T> order) {
        rows.stream().sorted(order).forEach(row -> lines.add(line(name, fields(row, skipped))));
    }

    private static List<String> fields(Object row, Set<String> skipped) {
        List<String> fields = new ArrayList<>();
        for (Field field : row.getClass().getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column == null || field.getName().equals("id") || field.getName().equals("closingId")
                    || skipped.contains(field.getName())) continue;
            try {
                field.setAccessible(true);
                fields.add(encode(field.get(row), column.scale()));
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return fields;
    }

    private static String line(String name, List<String> fields) {
        return name + "|" + String.join("|", fields);
    }

    static String encode(Object value, int scale) {
        if (value == null) return "";
        if (value instanceof Boolean flag) return flag ? "1" : "0";
        if (value instanceof BigDecimal number) return number.setScale(scale, RoundingMode.HALF_UP).toPlainString();
        if (value instanceof LocalDate date) return date.toString();
        if (value instanceof Instant instant) return instant.truncatedTo(ChronoUnit.SECONDS).toString();
        if (value instanceof Number number) return String.valueOf(number.longValue());
        return value.toString().replace("\\", "\\\\").replace("|", "\\|").replace("\r\n", "\\n")
                .replace("\n", "\\n").replace("\r", "\\n");
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
