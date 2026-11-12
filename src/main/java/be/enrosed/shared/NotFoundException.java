package be.enrosed.shared;

/** Thrown by the services; the mapper turns it into a 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String what, Object id) {
        super(what + " " + id + " bestaat niet");
    }

    /** A sentence of its own, for a screen that names what is missing in its own words. */
    public NotFoundException(String message) {
        super(message);
    }
}
