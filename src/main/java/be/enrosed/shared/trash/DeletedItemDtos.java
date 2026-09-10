package be.enrosed.shared.trash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Deliberately excludes portal tokens, storage keys and mutable operational payloads. */
public final class DeletedItemDtos {
    private DeletedItemDtos() {}
    public enum Type { INVOICE, QUOTE, PURCHASE_ORDER }
    public record Field(String label, String value) {}
    public record Line(String description, String sku, BigDecimal quantity, String unit,
                       BigDecimal unitPriceEur, BigDecimal totalEur) {}
    public record Attachment(long id, String name, String url) {}
    public record Snapshot(List<Field> fields, List<Line> lines, String notes,
                           List<Attachment> attachments, BigDecimal totalEur) {}
    public record Summary(long id, Type type, long sourceId, String number, String partyName,
                          String status, Instant deletedAt, Instant expiresAt, String deletedBy,
                          BigDecimal totalEur, boolean restoreAllowed, String blockReason) {}
    public record Detail(long id, Type type, long sourceId, String number, String partyName,
                         String status, Instant deletedAt, Instant expiresAt, String deletedBy,
                         BigDecimal totalEur, boolean restoreAllowed, String blockReason,
                         List<Field> fields, List<Line> lines, String notes, List<Attachment> attachments) {}
    public record Page(int retentionDays, List<Summary> items) {}
    public record Restored(long sourceId, String targetRoute) {}
}
