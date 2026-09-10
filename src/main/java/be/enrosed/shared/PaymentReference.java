package be.enrosed.shared;

/** Bank-friendly remittance text; the document's own number remains unchanged. */
public final class PaymentReference {
    private PaymentReference() {}

    public static String normalized(String value) {
        return value == null ? "" : value.replace('/', '-');
    }
}
