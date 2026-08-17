package be.enrosed.sales.domain;

import be.enrosed.shared.Language;

import java.time.LocalDate;

public record Customer(
        Long id,
        String company,
        String contact,
        String email,
        String phone,
        String vatNumber,
        String countryCode,
        /**
         * Taal waarin deze klant zijn offerte en mail krijgt.
         *
         * Hangt aan de klant en niet aan het land: een Belgische klant kan
         * Frans of Nederlands willen, en een inkoper bij een Duits bedrijf
         * werkt soms liever in het Engels.
         */
        Language language,
        String address,
        String postalCode,
        String city,
        String incoterm,
        String paymentTerms,
        String notes,
        LocalDate createdAt
) {
    public Language language() {
        return language == null ? Language.NL : language;
    }
}
