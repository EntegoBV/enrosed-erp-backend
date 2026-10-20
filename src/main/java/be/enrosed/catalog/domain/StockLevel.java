package be.enrosed.catalog.domain;

/** How many pieces of one product lie at one location. */
public record StockLevel(long productId, StockLocation location, int quantity, int loosePieces, int piecesPerUnit) {
    public StockLevel(long productId, StockLocation location, int quantity) {
        this(productId, location, quantity, 0, 1);
    }
    public int physicalQuantity() { return Math.addExact(Math.multiplyExact(quantity, piecesPerUnit), loosePieces); }
}
