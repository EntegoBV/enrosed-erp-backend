package be.enrosed.sales.domain;

import java.time.Instant;

/**
 * Eén stap in het leven van een offerte.
 *
 * De status van een order zegt waar hij nú staat, niet hoe hij daar gekomen is.
 * Bij een offerte die drie keer heen en weer is gegaan is dat tweede net wat je
 * nodig hebt: wat stelde de klant voor, wat hebben wij ervan overgenomen, en
 * wanneer. Zonder dat spoor is een order na een week niet meer uit te leggen -
 * niet aan de klant en niet aan onszelf.
 *
 * Gebeurtenissen worden alleen toegevoegd, nooit gewijzigd of verwijderd. Ook
 * een ingetrokken voorstel blijft staan: dat het is ingetrokken is zelf een
 * stap in het verhaal.
 */
public record QuoteEvent(
        Long id,
        Long salesOrderId,
        Type type,
        Instant at,
        /** Wie het deed: onze gebruikersnaam of de naam die de klant intikte. */
        String actor,
        /** Of het van de klantkant kwam; bepaalt hoe het in het scherm staat. */
        boolean byCustomer,
        /** Korte omschrijving in gewone taal. */
        String summary,
        /** Wat er verder bij hoort, bijvoorbeeld de gewijzigde aantallen. */
        String detail
) {

    public enum Type {
        OPGEMAAKT,
        VERSTUURD,
        BEKEKEN,
        VOORSTEL,
        VOORSTEL_INGETROKKEN,
        VOORSTEL_OVERGENOMEN,
        VOORSTEL_AFGEWEZEN,
        GETEKEND,
        AFGEWEZEN,
        HEROPEND,
        LEVERTERMIJN_INGEVULD,
        VRACHT_INGEVULD
    }
}
