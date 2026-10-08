package be.enrosed.inventory.application;

import be.enrosed.shared.BusinessRuleException;

import java.util.Map;

/**
 * A count or closing action that the current state does not allow: a second
 * open count for a location, a write on a final closing, figures that moved
 * since the screen read them. The code tells the screen which case it is and
 * the details carry what it needs to show; the Dutch message is shown as it is.
 */
public class InventoryRefusal extends BusinessRuleException {
    private final String code;
    private final Map<String, Object> details;

    public InventoryRefusal(String code, String message) {
        this(code, message, Map.of());
    }

    public InventoryRefusal(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public String code() { return code; }

    /** Never null; empty when the refusal has nothing to add to its message. */
    public Map<String, Object> details() { return details; }
}
