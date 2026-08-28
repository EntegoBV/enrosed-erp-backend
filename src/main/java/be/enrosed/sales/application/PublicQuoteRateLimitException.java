package be.enrosed.sales.application;

import be.enrosed.publicform.PublicFormRateLimitException;

public class PublicQuoteRateLimitException extends PublicFormRateLimitException {
    public PublicQuoteRateLimitException(long retryAfterSeconds) {
        super(retryAfterSeconds);
    }
}
