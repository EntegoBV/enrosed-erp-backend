package be.enrosed.publicform;

/** A required external anti-abuse check could not be completed safely. */
public class PublicFormServiceUnavailableException extends RuntimeException {
    public PublicFormServiceUnavailableException() {
        super("Public form verification is temporarily unavailable");
    }
}
