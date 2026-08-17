package be.enrosed.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Verkooporder, tevens het offertedocument dat naar de klant gaat.
 *
 * {@code portalToken} is de sleutel waarmee de klant de offerte opent zonder
 * account. Hij wordt pas aangemaakt bij het versturen en is lang en willekeurig;
 * wie hem heeft mag de offerte zien, wijzigen voorstellen en tekenen.
 */
public record SalesOrder(
        Long id,
        String number,
        Long customerId,
        String countryCode,
        LocalDate orderDate,
        LocalDate validUntil,
        QuoteStatus status,
        String incoterm,
        String notes,

        MarkupMode markupMode,
        BigDecimal orderMarkupPct,

        /**
         * Extra korting bovenop de staffels, bijvoorbeeld een beurskorting.
         * Optioneel: leeg of nul betekent geen extra korting.
         */
        BigDecimal extraDiscountPct,
        /** Waarom die korting er is; verschijnt zo op de offerte. */
        String extraDiscountLabel,

        String portalToken,
        Instant sentAt,
        Instant viewedAt,
        /** Hoe vaak de klant de offerte geopend heeft. */
        int viewCount,
        Instant decidedAt,
        /* Naam die de klant intikt bij het accepteren - de handtekening. */
        String signedByName,
        String customerMessage,
        /** Notities voor onszelf; komen nooit op het klantdocument. */
        String internalNotes,

        /**
         * Of er nog een levertermijn moest komen, en of die intussen ingevuld is.
         * Bepaalt wat de klant in het portaal en in de mail te lezen krijgt.
         */
        DeliveryTermsState deliveryTerms,

        /**
         * Of de vracht nog bepaald moet worden, en of dat intussen gebeurd is.
         * Werkt net als {@link #deliveryTerms}.
         */
        FreightState freight,

        /**
         * Vracht die wij zelf invullen in plaats van het landtarief te gebruiken.
         *
         * Leeg betekent: reken het tarief van het bestemmingsland. Staat de
         * vracht op "nog te bepalen", dan telt er nog niets mee in het totaal.
         */
        BigDecimal manualFreightEur,

        List<SalesOrderLine> lines
) {
    public List<SalesOrderLine> lines() {
        return lines == null ? List.of() : lines;
    }

    public DeliveryTermsState deliveryTerms() {
        return deliveryTerms == null ? DeliveryTermsState.VOLLEDIG : deliveryTerms;
    }

    public FreightState freight() {
        return freight == null ? FreightState.BEREKEND : freight;
    }

    /**
     * De opgeslagen waarde zonder terugval.
     *
     * Nodig bij het bijwerken: null betekent daar "het formulier stuurde dit
     * veld niet mee", en dat is iets anders dan "zet het op berekend".
     */
    public FreightState freightOrNull() {
        return freight;
    }
}
