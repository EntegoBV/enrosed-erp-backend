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
        int position
) {
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
