package be.enrosed.sourcing.domain;

/** Where an already recorded payment came from, independently of its bank evidence. */
public record PurchasePaymentOrigin(Source source, String payerName, String note) {
    public enum Source { OLD_BANK_ACCOUNT, OTHER_COMPANY }

    public String label() {
        String title = source == Source.OTHER_COMPANY ? "Betaald door " + payerName : "Betaald via oude bankrekening";
        return note == null || note.isBlank() ? title : title + " · " + note;
    }
}
