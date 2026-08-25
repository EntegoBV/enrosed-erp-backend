package be.enrosed.shared.company;

/**
 * Our own company details.
 *
 * In the database, not in configuration: an address change or a new bank
 * account should not require a server restart. These details appear on
 * every quote, invoice and catalogue.
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

    /**
     * Terms in the document's language. Dutch and English follow whatever
     * was edited in settings; French and German are maintained built-ins,
     * every other language reads the English version.
     */
    public String termsFor(be.enrosed.shared.Language language) {
        return switch (language) {
            case NL -> termsNl();
            case FR -> DefaultLegalTexts.TERMS_FR;
            case DE -> DefaultLegalTexts.TERMS_DE;
            default -> termsEn();
        };
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

    /** Address as one line, for a document header. */
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
