package be.enrosed.catalog.domain;

/** What, if anything, wraps the product on the shelf: nothing, a gift box or a display. */
public enum PackagingKind {
    NONE, GIFT_BOX, DISPLAY;

    public String dutchLabel() {
        return switch (this) {
            case NONE -> "";
            case GIFT_BOX -> "Geschenkverpakking";
            case DISPLAY -> "Display";
        };
    }
}
