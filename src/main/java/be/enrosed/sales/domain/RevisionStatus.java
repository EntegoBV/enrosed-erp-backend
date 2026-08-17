package be.enrosed.sales.domain;

public enum RevisionStatus {
    /** Ligt bij ons ter beoordeling. */
    IN_AFWACHTING,
    /** Overgenomen op de order. */
    GOEDGEKEURD,
    /** Niet overgenomen. */
    AFGEWEZEN,
    /**
     * Door de klant zelf ingetrokken voor wij eraan toekwamen.
     *
     * Bewust niet verwijderd: dat er even een voorstel gelegen heeft hoort bij
     * het verhaal van de offerte, ook als het weer weggehaald is.
     */
    INGETROKKEN
}
