package be.enrosed.catalog.domain;

import be.enrosed.shared.Currency;
import be.enrosed.shared.Language;

import java.math.BigDecimal;
import java.util.List;

/**
 * Artikel in de catalogus.
 *
 * Let op het onderscheid tussen drie dingen die makkelijk door elkaar lopen:
 *  - {@code dimensions} is het product zelf (15 x 30 cm)
 *  - {@code colour} is de kleur ("Rood", "Roze"), de eerste van wat later
 *    een reeks productopties kan worden
 *  - {@code carton} is de omdoos waarin het verscheept wordt
 */
public record Product(
        Long id,
        String sku,
        String name,
        Dimensions dimensions,
        /**
         * Kleur van het artikel.
         *
         * Eerste van wat later een reeks productopties kan worden (maat,
         * afwerking). Daarom staat de waarde apart en niet verwerkt in de
         * productnaam: zodra er een tweede optie bijkomt hoef je bestaande
         * gegevens niet uit elkaar te pluizen.
         */
        String colour,
        /**
         * Verkoopstekst voor op de offerte en in de catalogus.
         *
         * Optioneel; zonder beschrijving valt alles terug op naam en afmeting.
         */
        String description,
        Long categoryId,
        Long supplierId,
        boolean active,

        Barcodes barcodes,
        String hsCode,

        Carton carton,

        BigDecimal exwPrice,
        Currency exwCurrency,
        BigDecimal extraUnitCost,

        BigDecimal landedCostEur,
        String landedCostSource,

        BigDecimal markupPct,
        BigDecimal fixedSalesPriceEur,

        /** Aantal stuks op voorraad; groeit bij een ontvangen inkooporder. */
        int stockQuantity,

        List<Photo> photos,

        /**
         * Naam, beschrijving en kleur in andere talen.
         *
         * De velden hierboven blijven de basis: wat niet vertaald is valt daarop
         * terug. Zo blijft een product bruikbaar zolang de vertaling er nog niet
         * is, in plaats van als leeg vak op een offerte te belanden.
         */
        List<ProductText> texts
) {

    public List<Photo> photos() {
        return photos == null ? List.of() : photos;
    }

    public List<ProductText> texts() {
        return texts == null ? List.of() : texts;
    }

    /** De tekst in deze taal, of null als ze er niet is. */
    public ProductText textIn(Language language) {
        return texts().stream()
                .filter(text -> text.language() == language)
                .findFirst()
                .orElse(null);
    }

    /** Naam in deze taal, met terugval op de basisnaam. */
    public String nameIn(Language language) {
        ProductText text = textIn(language);
        return text == null || isBlank(text.name()) ? name : text.name();
    }

    /** Kleur in deze taal, met terugval op de basiskleur. */
    public String colourIn(Language language) {
        ProductText text = textIn(language);
        return text == null || isBlank(text.colour()) ? colour : text.colour();
    }

    /** Beschrijving in deze taal, met terugval op de basisbeschrijving. */
    public String descriptionIn(Language language) {
        ProductText text = textIn(language);
        return text == null || isBlank(text.description()) ? description : text.description();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Hoofdfoto: de eerste in de reeks. */
    public Photo primaryPhoto() {
        return photos().isEmpty() ? null : photos().get(0);
    }

    /** Volledige omschrijving voor op een offerte, in onze eigen taal. */
    public String describe() {
        return describeIn(Language.NL);
    }

    /**
     * Volledige omschrijving in de taal van de klant.
     *
     * De afmeting blijft in cijfers staan; die is in elke taal hetzelfde en
     * hoort niet in een vertaalbestand thuis.
     */
    public String describeIn(Language language) {
        String naam = nameIn(language);
        StringBuilder text = new StringBuilder(naam == null ? "" : naam);
        String size = dimensions == null ? "" : dimensions.label();
        if (!size.isBlank()) text.append(" - ").append(size);
        String kleur = colourIn(language);
        if (kleur != null && !kleur.isBlank()) text.append(" - ").append(kleur);
        return text.toString();
    }
}
