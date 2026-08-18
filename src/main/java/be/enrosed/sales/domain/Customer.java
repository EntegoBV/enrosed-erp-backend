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
         * Language this customer receives their quote and mail in.
         *
         * Hangs on the customer, not the country: a Belgian customer may
         * want French or Dutch, and a buyer at a German company sometimes
         * prefers working in English.
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
