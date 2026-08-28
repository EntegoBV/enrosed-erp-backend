package be.enrosed.publicform;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validation failure safe to expose without echoing submitted personal data. */
public class PublicFormValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public PublicFormValidationException(Map<String, String> fieldErrors) {
        super("The public form contains invalid or missing fields");
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
