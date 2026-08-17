package be.enrosed.shared.company;

/**
 * Onze eigen bedrijfsgegevens.
 *
 * Staan in de database en niet in de configuratie: een adreswijziging of een
 * nieuw rekeningnummer hoort geen herstart van de server te vragen. Deze
 * gegevens komen op elke offerte, factuur en catalogus.
 */
public record CompanyProfile(
        String name,
        String legalName,
        String vatNumber,
        String registrationNumber,

        String addressLine,
        String postalCode,
        String city,
        String countryCode,

        String email,
        String phone,
        String website,

        String iban,
        String bic,

        /** Verschijnt onderaan op documenten, bv. verwijzing naar de voorwaarden. */
        String documentFooter
) {
    public static CompanyProfile empty() {
        return new CompanyProfile("Enrosed", "", "", "",
                "", "", "", "BE", "", "", "", "", "", "");
    }

    /** Adres als één regel, voor in de kop van een document. */
    public String addressOneLine() {
        StringBuilder text = new StringBuilder();
        append(text, addressLine, ", ");
        append(text, join(postalCode, city), ", ");
        append(text, countryCode, "");
        return text.toString();
    }

    private static String join(String left, String right) {
        if (blank(left)) return right == null ? "" : right;
        if (blank(right)) return left;
        return left + " " + right;
    }

    private static void append(StringBuilder target, String value, String separator) {
        if (blank(value)) return;
        if (!target.isEmpty()) target.append(separator.isBlank() ? ", " : separator);
        target.append(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
