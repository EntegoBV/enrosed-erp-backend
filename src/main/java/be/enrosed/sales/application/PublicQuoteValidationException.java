package be.enrosed.sales.application;

import be.enrosed.publicform.PublicFormValidationException;

import java.util.Map;

/** Validation failure safe to expose without echoing submitted personal data. */
public class PublicQuoteValidationException extends PublicFormValidationException {
    public PublicQuoteValidationException(Map<String, String> fieldErrors) {
        super(fieldErrors);
    }
}
