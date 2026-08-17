package be.enrosed.catalog.domain;

import be.enrosed.shared.Language;

/**
 * De tekst van een product in één taal.
 *
 * Alleen naam, beschrijving en kleur staan hierin. De rest van een product -
 * afmetingen, barcodes, HS-code, doosinhoud - is universeel en blijft in het
 * Engels staan: die gegevens vertalen levert niets op en verdubbelt wel de kans
 * op tegenstrijdigheden.
 *
 * Een leeg veld betekent "nog niet vertaald" en valt terug op het product zelf.
 * Dat is bewust: liever de basisnaam op een Franse offerte dan een leeg vak.
 */
public record ProductText(
        Language language,
        String name,
        String description,
        String colour
) {

    /** Is er iets ingevuld? Een rij met enkel lege velden hoeft niet bewaard. */
    public boolean isEmpty() {
        return blank(name) && blank(description) && blank(colour);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
