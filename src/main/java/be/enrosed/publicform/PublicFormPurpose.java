package be.enrosed.publicform;

/** Public website forms that may create durable business data. */
public enum PublicFormPurpose {
    QUOTE,
    CONTACT,
    /** The three account forms of the website: login request, new link, log in. */
    ACCOUNT,
    /** Idempotency namespace of a logged-in quote request; its form token and challenge are QUOTE ones. */
    ACCOUNT_QUOTE
}
