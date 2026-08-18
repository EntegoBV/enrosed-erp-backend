package be.enrosed.shared;

/**
 * Thrown when an action collides with a domain rule - sending a quote
 * without lines, approving a proposal already handled. The mapper turns
 * this into a 409.
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
