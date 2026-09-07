package be.enrosed.sales.domain;

import be.enrosed.shared.Language;

import java.math.BigDecimal;
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
        LocalDate createdAt,
        /** A partner who co-orders containers at our landed cost and sells the goods at auction. */
        boolean partner,
        /** Our default share of that partner's auction profit, in percent; null means half. */
        BigDecimal partnerSharePct,
        /** The part of the landed cost the partner pays up front, in percent; null means all of it. */
        BigDecimal partnerCostPct
) {
    /** Compatibility for callers written before partner customers existed. */
    public Customer(Long id, String company, String contact, String email, String phone,
                    String vatNumber, String countryCode, Language language, String address,
                    String postalCode, String city, String incoterm, String paymentTerms,
                    String notes, LocalDate createdAt) {
        this(id, company, contact, email, phone, vatNumber, countryCode, language, address,
                postalCode, city, incoterm, paymentTerms, notes, createdAt, false, null, null);
    }

    /** What a partner customer shares by default: half the profit unless agreed otherwise. */
    public BigDecimal partnerSharePctOrDefault() {
        return partnerSharePct != null ? partnerSharePct : new BigDecimal("50");
    }

    /** What a partner pays up front by default: the whole landed cost. */
    public BigDecimal partnerCostPctOrDefault() {
        return partnerCostPct != null ? partnerCostPct : new BigDecimal("100");
    }

    public Language language() {
        return language == null ? Language.NL : language;
    }
}
