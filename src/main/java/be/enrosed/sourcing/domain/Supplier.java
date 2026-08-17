package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;

/** Leverancier. De munt bepaalt waarin nieuwe producten geprijsd worden. */
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
        String notes
) {}
