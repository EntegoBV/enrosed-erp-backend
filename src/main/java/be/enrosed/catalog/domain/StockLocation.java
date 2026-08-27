package be.enrosed.catalog.domain;

/**
 * A place where stock sits: the own warehouse, or a sales point such as a
 * TICA stand where pieces are stored and sold from.
 *
 * Only locations that count for the website feed the figure customers
 * see; what lies at a sales point is there to be sold on the spot.
 */
public record StockLocation(
        Long id,
        String code,
        String name,
        Kind kind,
        String address,
        boolean active,
        /** Adds to the stock the website and the portal show. */
        boolean countsForWebsite,
        /** Purchase receipts land here unless the order says otherwise. */
        boolean receivesByDefault,
        int position,
        /** Customer may select this active location when requesting collection. */
        boolean publicPickupPoint,
        /** Customer-facing name; deliberately separate from the internal stock name. */
        String publicPickupLabel,
        /** Complete customer-facing collection address. */
        String publicPickupAddress,
        /** Optional practical collection instructions. */
        String publicPickupInstructions,
        /** Ordering among the collection choices shown on the public website. */
        int publicPickupPosition
) {
    /** Compatibility for inventory callers written before public collection existed. */
    public StockLocation(Long id, String code, String name, Kind kind, String address,
                         boolean active, boolean countsForWebsite,
                         boolean receivesByDefault, int position) {
        this(id, code, name, kind, address, active, countsForWebsite, receivesByDefault,
                position, false, null, null, null, 0);
    }

    public enum Kind {
        WAREHOUSE, SALES_POINT;

        public String dutchLabel() {
            return switch (this) {
                case WAREHOUSE -> "Magazijn";
                case SALES_POINT -> "Verkooppunt";
            };
        }
    }

    /** Code for the location every catalogue started with. */
    public static final String MAIN_CODE = "MAIN";
}
