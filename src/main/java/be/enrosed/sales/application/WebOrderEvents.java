package be.enrosed.sales.application;

/**
 * What happened to a website order, fired inside the transaction and
 * observed only after it committed: the customer mails and staff notices
 * can therefore never roll an order back, and a rolled-back action sends none.
 */
public final class WebOrderEvents {
    private WebOrderEvents() {}

    public record Placed(long orderId, String number) {}
    public record Changed(long orderId, String number, int revision, String summary) {}
    public record Cancelled(long orderId, String number) {}
    public record Taken(long orderId, String number) {}
}
