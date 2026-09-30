package be.enrosed.sourcing.domain;

/**
 * A container as sales names it, read without loading the order and its lines.
 *
 * One rule everywhere: the "Herkenbare naam" (alias) when it is filled in,
 * else the order number, else "Inkoop #id". Our own name such as
 * "container/2026/002" then reads the same on every sales screen and document.
 */
public record PurchaseOrderName(long id, String number, String alias) {

    /** The name sales shows for this container. */
    public String displayName() {
        return display(id, number, alias);
    }

    /** The shared rule, also for a full {@link PurchaseOrder}. */
    public static String display(Long id, String number, String alias) {
        if (alias != null && !alias.isBlank()) return alias.strip();
        if (number != null && !number.isBlank()) return number.strip();
        return "Inkoop #" + (id == null ? "?" : id);
    }
}
