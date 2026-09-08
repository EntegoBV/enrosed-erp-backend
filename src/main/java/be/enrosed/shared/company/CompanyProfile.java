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
        String privacyPolicyEn,
        /** Our limited fiscal representative in the Netherlands, named on documents for customers cleared through it. */
        String fiscalRepresentativeName,
        /** That representative's VAT number. */
        String fiscalRepresentativeVat,
        /** The letters in front of quote numbers: ENR gives ENR-2026-0001. */
        String quoteNumberPrefix,
        /** The letters in front of invoice numbers: F gives F-2026-0001. */
        String invoiceNumberPrefix,
        /** How partner quotes are numbered, as a pattern: offerte/partner/{jaar}/{nr:3}. */
        String partnerQuoteNumberPattern,
        /** How partner invoices, advance and final, are numbered: partner/{jaar}/{nr:3}. */
        String partnerInvoiceNumberPattern,
        /** The sequence the partner quotes carry on from when the books already count further; null means from what exists. */
        Integer partnerQuoteNextNumber,
        /** The same for partner invoices. */
        Integer partnerInvoiceNextNumber
) {
    /** Compatibility for callers written before the partner series existed. */
    public CompanyProfile(String name, String legalName, String vatNumber, String registrationNumber,
                          String addressLine, String postalCode, String city, String countryCode,
                          String email, String phone, String website, String iban, String bic,
                          String documentFooter, String documentFooterEn, String termsAndConditions,
                          String termsAndConditionsEn, String privacyPolicy, String privacyPolicyEn,
                          String fiscalRepresentativeName, String fiscalRepresentativeVat,
                          String quoteNumberPrefix, String invoiceNumberPrefix) {
        this(name, legalName, vatNumber, registrationNumber, addressLine, postalCode, city, countryCode,
                email, phone, website, iban, bic, documentFooter, documentFooterEn, termsAndConditions,
                termsAndConditionsEn, privacyPolicy, privacyPolicyEn, fiscalRepresentativeName, fiscalRepresentativeVat,
                quoteNumberPrefix, invoiceNumberPrefix, DEFAULT_PARTNER_QUOTE_PATTERN, DEFAULT_PARTNER_INVOICE_PATTERN, null, null);
    }

    public static final String DEFAULT_PARTNER_QUOTE_PATTERN = "offerte/partner/{jaar}/{nr:3}";
    public static final String DEFAULT_PARTNER_INVOICE_PATTERN = "partner/{jaar}/{nr:3}";

    /** The pattern partner quotes are numbered by; the seeded one until settings hold a usable one. */
    public String partnerQuotePattern() {
        return partnerQuoteNumberPattern != null && partnerQuoteNumberPattern.contains("{nr")
                ? partnerQuoteNumberPattern.strip() : DEFAULT_PARTNER_QUOTE_PATTERN;
    }

    /** The pattern partner invoices are numbered by; the seeded one until settings hold a usable one. */
    public String partnerInvoicePattern() {
        return partnerInvoiceNumberPattern != null && partnerInvoiceNumberPattern.contains("{nr")
                ? partnerInvoiceNumberPattern.strip() : DEFAULT_PARTNER_INVOICE_PATTERN;
    }
    /** Compatibility for callers written before the number prefixes existed. */
    public CompanyProfile(String name, String legalName, String vatNumber, String registrationNumber,
                          String addressLine, String postalCode, String city, String countryCode,
                          String email, String phone, String website, String iban, String bic,
                          String documentFooter, String documentFooterEn, String termsAndConditions,
                          String termsAndConditionsEn, String privacyPolicy, String privacyPolicyEn,
                          String fiscalRepresentativeName, String fiscalRepresentativeVat) {
        this(name, legalName, vatNumber, registrationNumber, addressLine, postalCode, city, countryCode,
                email, phone, website, iban, bic, documentFooter, documentFooterEn, termsAndConditions,
                termsAndConditionsEn, privacyPolicy, privacyPolicyEn, fiscalRepresentativeName, fiscalRepresentativeVat,
                DEFAULT_QUOTE_PREFIX, DEFAULT_INVOICE_PREFIX, DEFAULT_PARTNER_QUOTE_PATTERN, DEFAULT_PARTNER_INVOICE_PATTERN, null, null);
    }

    public static final String DEFAULT_QUOTE_PREFIX = "ENR";
    public static final String DEFAULT_INVOICE_PREFIX = "F";

    /** The quote prefix, letters and digits only; the seeded one until settings say otherwise. */
    public String quotePrefix() {
        return cleanPrefix(quoteNumberPrefix, DEFAULT_QUOTE_PREFIX);
    }

    /** The invoice prefix, letters and digits only; the seeded one until settings say otherwise. */
    public String invoicePrefix() {
        return cleanPrefix(invoiceNumberPrefix, DEFAULT_INVOICE_PREFIX);
    }

    private static String cleanPrefix(String value, String fallback) {
        if (value == null) return fallback;
        String clean = value.strip().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return clean.isEmpty() ? fallback : clean;
    }
    /** Compatibility for callers written before the fiscal representative existed. */
    public CompanyProfile(String name, String legalName, String vatNumber, String registrationNumber,
                          String addressLine, String postalCode, String city, String countryCode,
                          String email, String phone, String website, String iban, String bic,
                          String documentFooter, String documentFooterEn, String termsAndConditions,
                          String termsAndConditionsEn, String privacyPolicy, String privacyPolicyEn) {
        this(name, legalName, vatNumber, registrationNumber, addressLine, postalCode, city, countryCode,
                email, phone, website, iban, bic, documentFooter, documentFooterEn, termsAndConditions,
                termsAndConditionsEn, privacyPolicy, privacyPolicyEn, DEFAULT_REPRESENTATIVE_NAME, DEFAULT_REPRESENTATIVE_VAT,
                DEFAULT_QUOTE_PREFIX, DEFAULT_INVOICE_PREFIX);
    }

    public static final String DEFAULT_REPRESENTATIVE_NAME = "24/7 Customs BV";
    public static final String DEFAULT_REPRESENTATIVE_VAT = "NL858617262B02";

    public static CompanyProfile empty() {
        /* Seeded with the real company identity: a fresh install should print
           correct documents before anyone has opened the settings screen. */
        return new CompanyProfile("Enrosed BV", "Enrosed BV", "BE 1034.273.386", "",
                "Vekeblok 17", "2400", "Mol", "BE", "", "", "", "", "", "", "",
                null, null, null, null, DEFAULT_REPRESENTATIVE_NAME, DEFAULT_REPRESENTATIVE_VAT,
                DEFAULT_QUOTE_PREFIX, DEFAULT_INVOICE_PREFIX, DEFAULT_PARTNER_QUOTE_PATTERN, DEFAULT_PARTNER_INVOICE_PATTERN, null, null);
    }

    /** The representative's name for documents; the seeded one until settings say otherwise. */
    public String representativeName() {
        return fiscalRepresentativeName == null || fiscalRepresentativeName.isBlank() ? DEFAULT_REPRESENTATIVE_NAME : fiscalRepresentativeName;
    }

    /** The representative's VAT number for documents; the seeded one until settings say otherwise. */
    public String representativeVat() {
        return fiscalRepresentativeVat == null || fiscalRepresentativeVat.isBlank() ? DEFAULT_REPRESENTATIVE_VAT : fiscalRepresentativeVat;
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
