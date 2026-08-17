package be.enrosed.sales.domain;

/**
 * Waar de levertermijnen van een offerte staan in het heen en weer met de klant.
 *
 * Een artikel zonder voorraad vertrekt met "levertermijn nog te bepalen". De
 * offerte moet dan terug naar ons: wij vullen de leverweek in en sturen ze
 * opnieuw. De klant moet bij die tweede zending meteen zien dat dat het is wat
 * er veranderd is - vandaar dat we het onthouden in plaats van het achteraf uit
 * de regels af te leiden.
 */
public enum DeliveryTermsState {

    /** Alles kon meteen beloofd worden; er is niets bijzonders te melden. */
    VOLLEDIG,

    /** Er vertrok een offerte met minstens één regel zonder termijn. */
    TE_BEPALEN,

    /** Die termijnen zijn intussen ingevuld en de offerte is opnieuw vertrokken. */
    AANGEVULD
}
