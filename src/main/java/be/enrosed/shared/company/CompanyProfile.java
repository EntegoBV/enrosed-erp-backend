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

        /** Appears at the bottom of documents, e.g. a note about the terms. */
        String documentFooter,
        /** The same footer in English; non-Dutch documents use this one. */
        String documentFooterEn,

        /**
         * The general terms and conditions, as plain text.
         *
         * Editable in settings and publicly readable: the quote PDF and the
         * customer portal link to them. Starts as a sensible draft for a
         * Belgian wholesale business so there is never a dead link.
         */
        String termsAndConditions,
        /** English terms; every non-Dutch document links to these. */
        String termsAndConditionsEn,
        /** GDPR privacy statement, Dutch. */
        String privacyPolicy,
        /** GDPR privacy statement, English. */
        String privacyPolicyEn
) {
    public static CompanyProfile empty() {
        /* Seeded with the real company identity: a fresh install should print
           correct documents before anyone has opened the settings screen. */
        return new CompanyProfile("Enrosed BV", "Enrosed BV", "BE 1034.273.386", "",
                "Vekeblok 17", "2400", "Mol", "BE", "", "", "", "", "", "", "",
                null, null, null, null);
    }

    /**
     * The footer in the document's language.
     *
     * Dutch documents get the Dutch text; every other language gets the
     * English one, like the legal texts. An empty English footer falls back
     * to Dutch: a Dutch line is better than a silent gap under a document.
     */
    public String footerFor(be.enrosed.shared.Language language) {
        if (language == be.enrosed.shared.Language.NL) return documentFooter;
        return documentFooterEn == null || documentFooterEn.isBlank()
                ? documentFooter : documentFooterEn;
    }

    /** Dutch terms, falling back to the built-in draft. */
    public String termsNl() {
        return orDefault(termsAndConditions, DefaultLegalTexts.TERMS_NL);
    }

    /** English terms, falling back to the built-in draft. */
    public String termsEn() {
        return orDefault(termsAndConditionsEn, DefaultLegalTexts.TERMS_EN);
    }

    public String privacyNl() {
        return orDefault(privacyPolicy, DefaultLegalTexts.PRIVACY_NL);
    }

    public String privacyEn() {
        return orDefault(privacyPolicyEn, DefaultLegalTexts.PRIVACY_EN);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
