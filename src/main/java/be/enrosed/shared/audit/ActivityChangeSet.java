package be.enrosed.shared.audit;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a compact audit-safe delta without ever serialising a complete request object.
 */
public final class ActivityChangeSet {

    private final List<ActivityChangeDto> changes = new ArrayList<>();

    private ActivityChangeSet() {}

    public static ActivityChangeSet create() {
        return new ActivityChangeSet();
    }

    public ActivityChangeSet add(String field, String label, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(new ActivityChangeDto(field, label, display(before), display(after)));
        }
        return this;
    }

    /** Records that sensitive or verbose content changed without copying its value into the log. */
    public ActivityChangeSet privateValue(String field, String label, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(new ActivityChangeDto(field, label, null, null));
        }
        return this;
    }

    public List<ActivityChangeDto> build() {
        return List.copyOf(changes);
    }

    private static String display(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool ? "Ja" : "Nee";
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof TemporalAccessor || value instanceof Enum<?>) return value.toString();
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }
}
