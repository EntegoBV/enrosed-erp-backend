package be.enrosed.shared;

/** Thrown by the services; the mapper turns it into a 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String what, Object id) {
        super(what + " " + id + " bestaat niet");
    }
}
