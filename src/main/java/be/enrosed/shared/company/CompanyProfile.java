package be.enrosed.shared.company;

/**
 * Onze eigen bedrijfsgegevens.
 *
 * Staan in de database en niet in de configuratie: een adreswijziging of een
 * nieuw rekeningnummer hoort geen herstart van de server te vragen. Deze
 * gegevens komen op elke offerte, factuur en catalogus.
 */
public record CompanyProfile(
        String name,
        String legalName,
        String vatNumber,
        String registrationNumber,

        String addressLine,
        String postalCode,
        String city,
        String countryCode,

        String email,
        String phone,
        String website,

        String iban,
        String bic,

        /** Verschijnt onderaan op documenten, bv. verwijzing naar de voorwaarden. */
        String documentFooter,

        /**
         * The general terms and conditions, as plain text.
         *
         * Editable in settings and publicly readable: the quote PDF and the
         * customer portal link to them. Starts as a sensible draft for a
         * Belgian wholesale business so there is never a dead link.
         */
        String termsAndConditions
) {
    public static CompanyProfile empty() {
        return new CompanyProfile("Enrosed", "", "", "",
                "", "", "", "BE", "", "", "", "", "", "", DEFAULT_TERMS);
    }

    /** Draft terms for a Belgian wholesaler; meant to be reviewed and edited. */
    public static final String DEFAULT_TERMS = """
Artikel 1 — Toepassing
Deze algemene voorwaarden gelden voor alle offertes, orderbevestigingen en leveringen van Enrosed. Afwijkingen gelden alleen wanneer ze schriftelijk zijn overeengekomen. Voorwaarden van de koper zijn niet van toepassing, ook niet aanvullend.

Artikel 2 — Offertes en prijzen
Offertes zijn vrijblijvend en geldig tot de datum die erop vermeld staat. Prijzen zijn in euro en exclusief BTW, tenzij anders vermeld. Kennelijke vergissingen of verschrijvingen binden ons niet.

Artikel 3 — Bestelling en aanvaarding
Een overeenkomst komt tot stand zodra de koper de offerte aanvaardt en wij die aanvaarding bevestigen. Aanvullingen of wijzigingen door de koper gelden pas na onze schriftelijke bevestiging.

Artikel 4 — Levering
Leverdata en leverweken zijn indicatief en gaan in vanaf onze bevestiging, onder voorbehoud van tussentijdse verkoop. Artikelen zonder voorraad worden op bestelling geproduceerd; de meegedeelde termijn wordt bevestigd zodra de productie is ingepland. Vertraging geeft geen recht op schadevergoeding of ontbinding, behalve na een schriftelijke ingebrekestelling waarbij een redelijke termijn van minstens dertig dagen is verstreken.

Artikel 5 — Vervoer en risico
Levering gebeurt volgens de incoterm op de offerte. Het risico gaat over op de koper op het ogenblik bepaald door die incoterm. Zichtbare transportschade moet bij ontvangst op de vrachtbrief worden genoteerd.

Artikel 6 — Eigendomsvoorbehoud
Geleverde goederen blijven onze eigendom tot volledige betaling van hoofdsom, kosten en interesten. Tot dan mag de koper ze niet verpanden of tot zekerheid overdragen; doorverkoop in de normale bedrijfsvoering is toegestaan.

Artikel 7 — Betaling
Facturen zijn betaalbaar binnen dertig dagen na factuurdatum, tenzij anders overeengekomen. Bij laattijdige betaling is van rechtswege en zonder ingebrekestelling een interest verschuldigd conform de wet van 2 augustus 2002 betreffende de betalingsachterstand bij handelstransacties, vermeerderd met een forfaitaire schadevergoeding van 10% van het factuurbedrag met een minimum van 125 euro.

Artikel 8 — Klachten
Klachten over zichtbare gebreken of over de conformiteit van de levering moeten schriftelijk gemeld worden binnen acht dagen na ontvangst. Verwerking of doorverkoop van de goederen geldt als aanvaarding. Natuurlijke producten zoals gepreserveerde bloemen kunnen onderling licht verschillen in kleur en vorm; zulke verschillen zijn geen gebrek.

Artikel 9 — Aansprakelijkheid
Onze aansprakelijkheid is beperkt tot het factuurbedrag van de betrokken levering. Wij zijn niet aansprakelijk voor onrechtstreekse schade zoals winstderving of imagoschade.

Artikel 10 — Overmacht
Gevallen van overmacht — waaronder productie- of transportonderbrekingen, havencongestie, uitvoer- of invoerbeperkingen — schorten onze verplichtingen op zolang ze duren, zonder recht op schadevergoeding.

Artikel 11 — Toepasselijk recht
Op alle overeenkomsten is het Belgisch recht van toepassing, met uitsluiting van het Weens Koopverdrag. Geschillen behoren tot de uitsluitende bevoegdheid van de rechtbanken van het gerechtelijk arrondissement Antwerpen.""";

    /** The stored terms, or the draft when nothing has been saved yet. */
    public String termsOrDefault() {
        return termsAndConditions == null || termsAndConditions.isBlank()
                ? DEFAULT_TERMS : termsAndConditions;
    }

    /** Adres als één regel, voor in de kop van een document. */
    public String addressOneLine() {
        StringBuilder text = new StringBuilder();
        append(text, addressLine, ", ");
        append(text, join(postalCode, city), ", ");
        append(text, countryCode, "");
        return text.toString();
    }

    private static String join(String left, String right) {
        if (blank(left)) return right == null ? "" : right;
        if (blank(right)) return left;
        return left + " " + right;
    }

    private static void append(StringBuilder target, String value, String separator) {
        if (blank(value)) return;
        if (!target.isEmpty()) target.append(separator.isBlank() ? ", " : separator);
        target.append(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
