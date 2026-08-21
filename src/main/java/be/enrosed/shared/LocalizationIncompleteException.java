package be.enrosed.shared;

import java.util.List;

/** Strict public/export locale cannot be built without falling back. */
public class LocalizationIncompleteException extends BusinessRuleException {
    private final List<String> missingPaths;

    public LocalizationIncompleteException(String message, List<String> missingPaths) {
        super(message);
        this.missingPaths = List.copyOf(missingPaths);
    }

    public List<String> missingPaths() {
        return missingPaths;
    }
}
