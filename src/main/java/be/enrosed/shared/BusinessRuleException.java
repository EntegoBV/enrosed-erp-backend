package be.enrosed.shared;

/**
 * Gegooid wanneer een actie botst met een regel uit het domein - een offerte
 * versturen zonder regels, een voorstel goedkeuren dat al behandeld is.
 * De mapper vertaalt dit naar een 409.
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
