package be.enrosed.shared;

/** Gegooid door de services; de mapper vertaalt het naar een 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String what, Object id) {
        super(what + " " + id + " bestaat niet");
    }
}
