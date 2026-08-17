package be.enrosed.sales.domain;

/**
 * Waar de vrachtkosten van een offerte staan in het heen en weer met de klant.
 *
 * Dezelfde weg als de levertermijn: soms weet je bij het opmaken nog niet wat
 * het transport kost - een bestemming buiten de gewone tarieven, een order die
 * net over een pallet gaat, of een klant die zelf laat ophalen. Dan vertrekt de
 * offerte met de vracht als open post, komt ze terug naar ons, vullen wij het
 * bedrag in en gaat ze opnieuw naar de klant.
 *
 * Het alternatief - een bedrag verzinnen en later corrigeren - is erger: de
 * klant rekent op het totaal dat er stond.
 */
public enum FreightState {

    /** Het berekende tarief geldt; er is niets bijzonders te melden. */
    BEREKEND,

    /** De offerte vertrok met de vracht als open post. */
    TE_BEPALEN,

    /** De vracht is intussen ingevuld en de offerte is opnieuw vertrokken. */
    AANGEVULD
}
