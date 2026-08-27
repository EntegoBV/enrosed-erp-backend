package be.enrosed.shared;

/**
 * A valid request shape that asks for a domain change the editor cannot safely perform.
 * Unlike an optimistic-lock conflict, reloading will not make this operation valid.
 */
public class UnprocessableBusinessRuleException extends BusinessRuleException {
    public UnprocessableBusinessRuleException(String message) {
        super(message);
    }
}
