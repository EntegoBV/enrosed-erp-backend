package be.enrosed.sales.domain;

/**
 * Levensloop van een offerte.
 *
 * CONCEPT -> VERZONDEN -> BEKEKEN -> GEACCEPTEERD
 *                              \-> WIJZIGING_GEVRAAGD -> (wij passen aan) -> VERZONDEN
 *                              \-> AFGEWEZEN
 *
 * De klant kan zelf niets definitief maken behalve accepteren of afwijzen;
 * een wijziging is altijd een voorstel dat wij nog moeten goedkeuren.
 */
public enum QuoteStatus {
    CONCEPT,
    VERZONDEN,
    BEKEKEN,
    WIJZIGING_GEVRAAGD,
    GEACCEPTEERD,
    AFGEWEZEN,
    VERLOPEN;

    public boolean isOpenForCustomer() {
        return this == VERZONDEN || this == BEKEKEN || this == WIJZIGING_GEVRAAGD;
    }

    public boolean isFinal() {
        return this == GEACCEPTEERD || this == AFGEWEZEN || this == VERLOPEN;
    }

    /**
     * Kan deze offerte terug naar concept?
     *
     * Een afgewezen of verlopen offerte is vaak geen eindpunt maar een
     * onderhandeling: de klant vond het te duur, wij passen de prijs aan en
     * sturen opnieuw. Een aanvaarde offerte niet - daar is voor getekend, en
     * die achteraf openbreken maakt onduidelijk waar de handtekening bij hoort.
     * Daarvoor maak je een nieuwe offerte.
     */
    public boolean canReopen() {
        return this == AFGEWEZEN || this == VERLOPEN;
    }
}
