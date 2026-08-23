package be.enrosed.sourcing.domain;

/**
 * What the agreed unit price covers.
 *
 * EXW: the price at the factory gate - freight, local costs and import
 * duty are added on top. DDP: delivered with duty paid - the price already
 * holds the road and the customs, so nothing is added.
 */
public enum PriceBasis {
    EXW, DDP;

    public String dutchLabel() {
        return this == DDP ? "DDP - geleverd incl. rechten" : "EXW - af fabriek";
    }
}
