package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Supplier. The currency decides what new products are priced in.
 *
 * <p>The address stays flat in the API so existing supplier forms remain
 * compatible. {@code name} is the company name and {@code country} is the
 * existing ISO country code. Address fields are optional because suppliers
 * created before these columns existed do not have them yet.</p>
 */
public record Supplier(
        Long id,
        String name,
        String country,
        String city,
        String contact,
        String email,
        String phone,
        Currency currency,
        String incoterm,
        String portOfLoading,
        int leadTimeDays,
        String notes,
        String addressLine1,
        String addressLine2,
        String postalCode,
        String region
) {
    /** Source-compatible constructor for existing callers and legacy seed data. */
    public Supplier(Long id, String name, String country, String city, String contact,
                    String email, String phone, Currency currency, String incoterm,
                    String portOfLoading, int leadTimeDays, String notes) {
        this(id, name, country, city, contact, email, phone, currency, incoterm,
                portOfLoading, leadTimeDays, notes, null, null, null, null);
    }

    /** Copies a request while keeping the path id authoritative on PUT. */
    public Supplier withId(Long newId) {
        return new Supplier(newId, name, country, city, contact, email, phone, currency,
                incoterm, portOfLoading, leadTimeDays, notes,
                addressLine1, addressLine2, postalCode, region);
    }

    /** Address lines in a compact international order suitable for documents. */
    public List<String> documentAddressLines() {
        List<String> lines = new ArrayList<>();
        add(lines, addressLine1);
        add(lines, addressLine2);
        add(lines, localityLine());
        add(lines, countryDocumentLine());
        return List.copyOf(lines);
    }

    private String localityLine() {
        String locality = join(postalCode, city);
        if (blank(region)) return locality;
        return blank(locality) ? region : locality + ", " + region.strip();
    }

    private String countryDocumentLine() {
        if (blank(country)) return null;
        String code = country.strip().toUpperCase(Locale.ROOT);
        if (code.length() != 2) return code;
        String name = Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
        return blank(name) || name.equalsIgnoreCase(code)
                ? code : name.toUpperCase(Locale.ENGLISH) + " (" + code + ")";
    }

    private static String join(String left, String right) {
        if (blank(left)) return right;
        if (blank(right)) return left;
        return left.strip() + " " + right.strip();
    }

    private static void add(List<String> lines, String value) {
        if (!blank(value)) lines.add(value.strip());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
