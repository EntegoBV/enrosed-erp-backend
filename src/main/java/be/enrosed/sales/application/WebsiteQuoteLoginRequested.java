package be.enrosed.sales.application;

/**
 * A website quote request whose sender also asked for a login. The marker starts the
 * line in the order's internal note that tells staff so.
 */
public record WebsiteQuoteLoginRequested(long customerId, long orderId, String orderNumber, String language,
        String companyName, String companyCountryCode, String vatNumber, String contactName, String email,
        String phone) { public static final String NOTE_MARKER = "[LOGIN_AANVRAAG]"; }
