package be.enrosed.sourcing.domain;

import java.util.Locale;

/**
 * Human-readable stages of a purchase order's physical cost route.
 *
 * <p>These labels live on the server so the editor, read-only view and PDF
 * describe exactly the same route. Supplier fields are optional for legacy
 * data; every stage therefore has a useful fallback.</p>
 */
public record PurchaseCostLabels(
        String originCountry,
        String loadingPort,
        String destinationPort,
        String originCostsLabel,
        String originRoute,
        String seaFreightLabel,
        String seaFreightRoute,
        String destinationCostsLabel
) {
    private static final Locale DUTCH = Locale.forLanguageTag("nl-NL");

    public static PurchaseCostLabels forOrder(PurchaseOrder order, Supplier supplier) {
        String originCountry = countryName(supplier == null ? null : supplier.country());
        String loadingPort = first(
                supplier == null ? null : supplier.portOfLoading(),
                supplier == null ? null : supplier.city(),
                "laadhaven");
        String destinationPort = first(order == null ? null : order.destinationPort(),
                null, "Rotterdam");

        return new PurchaseCostLabels(
                originCountry,
                loadingPort,
                destinationPort,
                "Lokale kosten " + originCountry,
                "Fabriek → " + loadingPort,
                "Zeevracht",
                loadingPort + " → " + destinationPort,
                destinationPort + " → magazijn");
    }

    private static String countryName(String code) {
        if (code == null || code.isBlank()) return "land van oorsprong";
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() != 2) return normalized;
        String name = Locale.of("", normalized).getDisplayCountry(DUTCH);
        return name == null || name.isBlank() || name.equalsIgnoreCase(normalized)
                ? normalized : name;
    }

    private static String first(String preferred, String alternative, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred.strip();
        if (alternative != null && !alternative.isBlank()) return alternative.strip();
        return fallback;
    }
}
