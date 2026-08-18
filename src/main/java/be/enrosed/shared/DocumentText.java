package be.enrosed.shared;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * De teksten die op een offerte, in een mail en in het klantportaal staan, per
 * taal.
 *
 * Bewust geen resource bundles met .properties-bestanden. Het gaat om één
 * document met een goede honderd woorden; een map die je in één scherm
 * overziet is hier makkelijker na te lezen dan vier bestanden waarin je moet
 * zoeken of een sleutel wel overal bestaat. De test zorgt dat geen enkele taal
 * een sleutel mist.
 *
 * Wat er niet in staat: productnamen en kleuren. Die komen uit de
 * productvertalingen, want die verandert de klant zelf via het CSV-bestand.
 */
public final class DocumentText {

    private DocumentText() {}

    /** De teksten voor deze taal, klaar om aan een sjabloon mee te geven. */
    public static Map<String, String> of(Language language) {
        return switch (language) {
            case NL -> NL_TEXT;
            case FR -> FR_TEXT;
            case EN -> EN_TEXT;
            case DE -> DE_TEXT;
            case ES -> ES_TEXT;
            case PL -> PL_TEXT;
            case PT -> PT_TEXT;
            case TR -> TR_TEXT;
        };
    }

    /**
     * De datum in de vorm die bij de taal past.
     *
     * Nederlands, Frans en Duits schrijven dag-maand-jaar; Engels krijgt de
     * maand voluit, want 03/04 leest een Britse en een Amerikaanse lezer
     * verschillend en bij een levertermijn wil je daar geen twijfel over.
     */
    public static String date(LocalDate date, Language language) {
        if (date == null) return "";
        return switch (language) {
            /* Het grootste deel van Europa schrijft dag-maand-jaar. */
            case NL, FR, DE, ES, PT, TR -> DocumentFormat.be(date);
            /* Polen schrijft met punten: 25.05.2026. */
            case PL -> date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            /* Engels krijgt de maand voluit: 05/25 en 25/05 lezen aan weerszijden
               van de oceaan anders, en bij een levertermijn wil je daar geen
               twijfel over. */
            case EN -> date.format(java.time.format.DateTimeFormatter
                    .ofPattern("d MMMM yyyy", java.util.Locale.UK));
        };
    }

    /** Bedrag met het scheidingsteken dat bij de taal hoort. */
    public static String money(java.math.BigDecimal amount, Language language) {
        if (amount == null) return "";
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(language.locale());
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }

    /**
     * Een leverweek uitgeschreven: "week 42 (12/10 - 18/10/2026)".
     *
     * De weeknummering zelf is overal gelijk; alleen het woord ervoor en de
     * datumopmaak verschillen.
     */
    public static String week(String isoWeek, Language language) {
        if (isoWeek == null || isoWeek.isBlank()) return "";
        java.util.regex.Matcher match =
                java.util.regex.Pattern.compile("^(\\d{4})-W(\\d{1,2})$").matcher(isoWeek.trim());
        if (!match.matches()) return isoWeek;

        int year = Integer.parseInt(match.group(1));
        int number = Integer.parseInt(match.group(2));
        String word = of(language).get("week");
        try {
            LocalDate monday = LocalDate.of(year, 1, 4)
                    .with(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear(), number)
                    .with(java.time.DayOfWeek.MONDAY);
            LocalDate sunday = monday.plusDays(6);
            return "%s %d (%s - %s)".formatted(word, number,
                    monday.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")),
                    date(sunday, language));
        } catch (RuntimeException e) {
            return word + " " + number;
        }
    }

    /* ------------------------------------------------------------ talen */

    private static Map<String, String> bundle(String... pairs) {
        Map<String, String> text = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            text.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(text);
    }

    private static final Map<String, String> NL_TEXT = bundle(
            "quote", "Offerte",
            "date", "Datum",
            "validUntil", "Geldig tot",
            "incoterm", "Incoterm",
            "customer", "Klant",
            "noCustomer", "Geen klant gekoppeld",
            "vat", "BTW",
            "vatNumber", "BTW-nummer",
            "buyerVatNumber", "BTW-nummer afnemer",
            "delivery", "Levering",
            "destination", "Bestemming",
            "pallets", "Pallets",
            "cartons", "Dozen",
            "payment", "Betaling",
            "sku", "SKU",
            "description", "Omschrijving",
            "quantity", "Aantal",
            "unitPrice", "Stukprijs",
            "discount", "Korting",
            "total", "Totaal",
            "subtotal", "Subtotaal",
            "orderDiscount", "Orderkorting",
            "extraDiscount", "Extra korting",
            "goodsValue", "Goederenwaarde",
            "freight", "Vracht",
            "handling", "Administratie",
            "totalInclVat", "Totaal incl. BTW",
            "note", "Opmerking",
            "from", "vanaf",
            "week", "week",
            "toBeAgreed", "in overleg",
            "freightToBeDetermined", "wordt nog bepaald",
            "portalTitle", "Online bekijken, tekenen of wijzigen",
            "portalText", "Deze offerte staat ook online. Daar kan u ze aanvaarden en digitaal"
                    + " tekenen, of een wijziging voorstellen die wij dan nakijken.",
            "footer", "Alle prijzen in euro. Leverdata gelden vanaf bevestiging en onder voorbehoud"
                    + " van tussentijdse verkoop; artikelen zonder voorraad worden op bestelling"
                    + " geproduceerd.",
            "validUntilSentence", "Deze offerte is geldig tot %s.",
            "termsSentence", "Op al onze offertes zijn onze algemene voorwaarden van toepassing.",
            /* mail */
            "mailSubject", "Offerte %s van Enrosed",
            "mailSubjectTermsAdded", "Levertermijn ingevuld - offerte %s van Enrosed",
            "mailGreeting", "Beste",
            "mailIntro", "In bijlage vindt u onze offerte %s.",
            "mailIntroUpdated", "In bijlage vindt u de aangepaste offerte %s.",
            "mailTermsAddedTitle", "Levertermijn ingevuld",
            "mailTermsAddedText", "U koos artikelen die niet op voorraad lagen. Wij hebben ze"
                    + " nagekeken en voor elk artikel een leverdatum of leverweek vastgelegd. Ze"
                    + " staan hieronder en op de bijgevoegde offerte, die u nu kan tekenen.",
            "mailDeliveryTitle", "Levertermijn",
            "mailDeliveryPerItem", "Levertermijn per artikel",
            "mailDeliveryPending", "Voor de artikelen zonder termijn nemen wij contact op zodra we"
                    + " die kunnen bevestigen. U krijgt dan een aangepaste offerte in uw mailbox.",
            "mailFreightPending", "De vrachtkosten worden nog bepaald. Zodra ze vastliggen sturen"
                    + " wij u een aangepaste offerte.",
            "mailButton", "Offerte online bekijken",
            "mailClosing", "Met vriendelijke groeten",
            /* portaal */
            "portalYourQuote", "Uw offerte",
            "portalStatusOpen", "Ter beoordeling",
            "portalStatusAccepted", "Aanvaard",
            "portalStatusRejected", "Afgewezen",
            "portalStatusRevision", "Wijziging in behandeling",
            "portalAccept", "Aanvaarden en tekenen",
            "portalPropose", "Wijziging voorstellen",
            "portalReject", "Afwijzen",
            "portalDownload", "PDF downloaden",
            "portalDeliverableFrom", "Leverbaar vanaf",
            "portalDeliveryInWeek", "Levering in",
            "portalTermToBeDetermined", "Levertermijn nog te bepalen",
            "portalPerPiece", "per stuk",
            "portalPieces", "stuks",
            "portalTermsAddedTitle", "Levertermijn toegevoegd",
            "portalTermsAddedText", "Wij hebben voor alle artikelen een leverdatum of leverweek"
                    + " ingevuld. U vindt ze hieronder per regel terug; daarna kan u tekenen.",
            "portalTermsPendingTitle", "Eén of meer artikelen liggen niet op voorraad",
            "portalTermsPendingText", "Wij bekijken wanneer wij die kunnen leveren en sturen u deze"
                    + " offerte opnieuw met de levertermijn erbij. U hoeft nu niets te doen; u mag"
                    + " uiteraard al tekenen of een wijziging voorstellen.",
            "portalFreightPendingTitle", "Vrachtkosten worden nog bepaald",
            "portalFreightPendingText", "Het bedrag hieronder is nog zonder vracht. Zodra wij de"
                    + " vrachtkosten kennen sturen wij u een aangepaste offerte.",
            "portalAddItem", "Artikel bijbestellen",
            "portalSearch", "Zoek in ons assortiment…",
            "portalYourName", "Uw naam",
            "portalComment", "Toelichting",
            "portalCancel", "Annuleren",
            "portalSend", "Voorstel versturen",
            "portalAdd", "Toevoegen",
            "portalInStock", "op voorraad",
            "portalOutOfStock", "op bestelling",
            "portalPerBox", "per doos",
            "portalRoundingNotice", "Wordt zo",
            "catalogCarton", "omdoos",
            "catalogTitle", "Productcatalogus",
            "catalogItems", "artikel(en)",
            "catalogFooter", "Prijzen per stuk in euro, exclusief BTW en vracht, onder voorbehoud van wijziging. Artikelen zonder voorraad worden op bestelling geproduceerd; de levertermijn spreken we samen af.",
            "portalTerms", "Algemene voorwaarden",
            "portalProposalPending", "Uw wijzigingsvoorstel ligt bij ons",
            "portalProposalPendingText", "Wij kijken uw voorstel na en sturen u een aangepaste offerte terug. U kan uw voorstel intrekken zolang wij het niet behandeld hebben.",
            "portalWithdraw", "Voorstel intrekken",
            "portalWithdrawn", "Uw voorstel is ingetrokken. U kan de offerte weer tekenen of een nieuw voorstel doen.",
            "portalNotFound", "Offerte niet gevonden",
            "portalNotFoundText", "Deze link is niet meer geldig. Neem gerust contact op, dan sturen we een nieuwe.",
            "portalFor", "voor",
            "portalValidUntil", "geldig tot",
            "portalBoxes", "dozen",
            "portalPalletsShort", "pallet(s)",
            "portalDiscount", "korting",
            "portalSignedBy", "Getekend door",
            "portalSignedText", "Wij nemen contact op voor de bevestiging.",
            "portalProposalSent", "Uw wijzigingsvoorstel is doorgestuurd.",
            "portalProposalApproved", "Uw wijziging is verwerkt.",
            "portalProposalRejected", "Uw voorstel is niet overgenomen.",
            "portalWhatNext", "Wat wil u doen?",
            "portalRejectQuote", "Offerte afwijzen",
            "portalPdf", "Offerte als PDF",
            "portalLoading", "Laden…",
            "portalSignTitle", "Aanvaarden en tekenen",
            "portalSignText", "Door uw naam in te vullen aanvaardt u deze offerte. Dat geldt als uw digitale handtekening.",
            "portalSignButton", "Tekenen",
            "portalNoteOptional", "Opmerking (optioneel)",
            "portalProposeText", "Pas de aantallen aan die u wil wijzigen. Wij kijken uw voorstel na en sturen een aangepaste offerte terug. Zet een aantal op 0 om een regel te laten vervallen.",
            "portalOnYourQuote", "Op uw offerte",
            "portalAddSection", "Iets bijbestellen",
            "portalOutOfStockWarning", "U koos een artikel dat niet op voorraad ligt. Wij moeten dat eerst aanvaarden en laten u de levertermijn weten; daarna krijgt u een aangepaste offerte om te tekenen.",
            "portalReasonOptional", "Reden (optioneel)",
            "portalOptional", "optioneel",
            "portalFooter", "Vragen? Antwoord gerust op de mail met deze offerte.");

    private static final Map<String, String> FR_TEXT = bundle(
            "quote", "Offre",
            "date", "Date",
            "validUntil", "Valable jusqu'au",
            "incoterm", "Incoterm",
            "customer", "Client",
            "noCustomer", "Aucun client lié",
            "vat", "TVA",
            "vatNumber", "N° de TVA",
            "buyerVatNumber", "N° de TVA de l'acheteur",
            "delivery", "Livraison",
            "destination", "Destination",
            "pallets", "Palettes",
            "cartons", "Cartons",
            "payment", "Paiement",
            "sku", "Réf.",
            "description", "Désignation",
            "quantity", "Quantité",
            "unitPrice", "Prix unitaire",
            "discount", "Remise",
            "total", "Total",
            "subtotal", "Sous-total",
            "orderDiscount", "Remise sur commande",
            "extraDiscount", "Remise supplémentaire",
            "goodsValue", "Valeur des marchandises",
            "freight", "Transport",
            "handling", "Frais de dossier",
            "totalInclVat", "Total TVA comprise",
            "note", "Remarque",
            "from", "à partir du",
            "week", "semaine",
            "toBeAgreed", "à convenir",
            "freightToBeDetermined", "à déterminer",
            "portalTitle", "Consulter, signer ou modifier en ligne",
            "portalText", "Cette offre est également disponible en ligne. Vous pouvez l'accepter et"
                    + " la signer électroniquement, ou proposer une modification que nous"
                    + " examinerons.",
            "footer", "Tous les prix en euros. Les délais de livraison courent à partir de la"
                    + " confirmation et sous réserve de vente entre-temps; les articles non en stock"
                    + " sont produits sur commande.",
            "validUntilSentence", "Cette offre est valable jusqu'au %s.",
            "termsSentence", "Toutes nos offres sont soumises à nos conditions générales.",
            "mailSubject", "Offre %s d'Enrosed",
            "mailSubjectTermsAdded", "Délai de livraison précisé - offre %s d'Enrosed",
            "mailGreeting", "Bonjour",
            "mailIntro", "Vous trouverez en pièce jointe notre offre %s.",
            "mailIntroUpdated", "Vous trouverez en pièce jointe l'offre %s adaptée.",
            "mailTermsAddedTitle", "Délai de livraison précisé",
            "mailTermsAddedText", "Vous aviez choisi des articles qui n'étaient pas en stock. Nous"
                    + " les avons vérifiés et fixé une date ou une semaine de livraison pour chacun."
                    + " Vous les trouvez ci-dessous et sur l'offre jointe, que vous pouvez"
                    + " maintenant signer.",
            "mailDeliveryTitle", "Délai de livraison",
            "mailDeliveryPerItem", "Délai de livraison par article",
            "mailDeliveryPending", "Pour les articles sans délai, nous vous recontactons dès que"
                    + " nous pouvons le confirmer. Vous recevrez alors une offre adaptée.",
            "mailFreightPending", "Les frais de transport restent à déterminer. Dès qu'ils seront"
                    + " fixés, nous vous enverrons une offre adaptée.",
            "mailButton", "Consulter l'offre en ligne",
            "mailClosing", "Cordialement",
            "portalYourQuote", "Votre offre",
            "portalStatusOpen", "En attente",
            "portalStatusAccepted", "Acceptée",
            "portalStatusRejected", "Refusée",
            "portalStatusRevision", "Modification en cours",
            "portalAccept", "Accepter et signer",
            "portalPropose", "Proposer une modification",
            "portalReject", "Refuser",
            "portalDownload", "Télécharger le PDF",
            "portalDeliverableFrom", "Livrable à partir du",
            "portalDeliveryInWeek", "Livraison en",
            "portalTermToBeDetermined", "Délai de livraison à déterminer",
            "portalPerPiece", "par pièce",
            "portalPieces", "pièces",
            "portalTermsAddedTitle", "Délai de livraison ajouté",
            "portalTermsAddedText", "Nous avons fixé une date ou une semaine de livraison pour"
                    + " chaque article. Vous les trouvez ci-dessous par ligne; vous pouvez ensuite"
                    + " signer.",
            "portalTermsPendingTitle", "Un ou plusieurs articles ne sont pas en stock",
            "portalTermsPendingText", "Nous vérifions quand nous pouvons les livrer et vous"
                    + " renvoyons cette offre avec le délai. Vous n'avez rien à faire pour"
                    + " l'instant; vous pouvez bien sûr déjà signer ou proposer une modification.",
            "portalFreightPendingTitle", "Frais de transport à déterminer",
            "portalFreightPendingText", "Le montant ci-dessous est encore hors transport. Dès que"
                    + " nous connaissons les frais, nous vous envoyons une offre adaptée.",
            "portalAddItem", "Ajouter un article",
            "portalSearch", "Rechercher dans notre gamme…",
            "portalYourName", "Votre nom",
            "portalComment", "Commentaire",
            "portalCancel", "Annuler",
            "portalSend", "Envoyer la proposition",
            "portalAdd", "Ajouter",
            "portalInStock", "en stock",
            "portalOutOfStock", "sur commande",
            "portalPerBox", "par carton",
            "portalRoundingNotice", "Sera arrondi à",
            "catalogCarton", "carton maître",
            "catalogTitle", "Catalogue de produits",
            "catalogItems", "article(s)",
            "catalogFooter", "Prix unitaires en euros, hors TVA et transport, sous réserve de modification. Les articles non en stock sont produits sur commande; le délai de livraison est convenu ensemble.",
            "portalTerms", "Conditions générales",
            "portalProposalPending", "Votre proposition est chez nous",
            "portalProposalPendingText", "Nous examinons votre proposition et vous renvoyons une offre adaptée. Vous pouvez retirer votre proposition tant que nous ne l'avons pas traitée.",
            "portalWithdraw", "Retirer la proposition",
            "portalWithdrawn", "Votre proposition a été retirée. Vous pouvez à nouveau signer l'offre ou faire une nouvelle proposition.",
            "portalNotFound", "Offre introuvable",
            "portalNotFoundText", "Ce lien n'est plus valable. N'hésitez pas à nous contacter, nous vous en enverrons un nouveau.",
            "portalFor", "pour",
            "portalValidUntil", "valable jusqu'au",
            "portalBoxes", "cartons",
            "portalPalletsShort", "palette(s)",
            "portalDiscount", "remise",
            "portalSignedBy", "Signé par",
            "portalSignedText", "Nous vous contactons pour la confirmation.",
            "portalProposalSent", "Votre proposition de modification a été transmise.",
            "portalProposalApproved", "Votre modification a été prise en compte.",
            "portalProposalRejected", "Votre proposition n'a pas été retenue.",
            "portalWhatNext", "Que souhaitez-vous faire ?",
            "portalRejectQuote", "Refuser l'offre",
            "portalPdf", "Offre en PDF",
            "portalLoading", "Chargement…",
            "portalSignTitle", "Accepter et signer",
            "portalSignText", "En indiquant votre nom, vous acceptez cette offre. Cela vaut signature électronique.",
            "portalSignButton", "Signer",
            "portalNoteOptional", "Remarque (facultatif)",
            "portalProposeText", "Adaptez les quantités que vous souhaitez modifier. Nous examinons votre proposition et vous renvoyons une offre adaptée. Mettez une quantité à 0 pour supprimer une ligne.",
            "portalOnYourQuote", "Sur votre offre",
            "portalAddSection", "Ajouter des articles",
            "portalOutOfStockWarning", "Vous avez choisi un article qui n'est pas en stock. Nous devons d'abord l'accepter et vous communiquerons le délai de livraison; vous recevrez ensuite une offre adaptée à signer.",
            "portalReasonOptional", "Motif (facultatif)",
            "portalOptional", "facultatif",
            "portalFooter", "Des questions ? Répondez simplement au courriel accompagnant cette offre.");

    private static final Map<String, String> EN_TEXT = bundle(
            "quote", "Quotation",
            "date", "Date",
            "validUntil", "Valid until",
            "incoterm", "Incoterm",
            "customer", "Customer",
            "noCustomer", "No customer linked",
            "vat", "VAT",
            "vatNumber", "VAT number",
            "buyerVatNumber", "Buyer VAT number",
            "delivery", "Delivery",
            "destination", "Destination",
            "pallets", "Pallets",
            "cartons", "Cartons",
            "payment", "Payment",
            "sku", "SKU",
            "description", "Description",
            "quantity", "Quantity",
            "unitPrice", "Unit price",
            "discount", "Discount",
            "total", "Total",
            "subtotal", "Subtotal",
            "orderDiscount", "Order discount",
            "extraDiscount", "Additional discount",
            "goodsValue", "Goods value",
            "freight", "Freight",
            "handling", "Handling",
            "totalInclVat", "Total incl. VAT",
            "note", "Note",
            "from", "from",
            "week", "week",
            "toBeAgreed", "to be agreed",
            "freightToBeDetermined", "to be determined",
            "portalTitle", "View, sign or amend online",
            "portalText", "This quotation is also available online. There you can accept and sign"
                    + " it digitally, or propose a change for us to review.",
            "footer", "All prices in euro. Delivery dates apply from confirmation and are subject to"
                    + " prior sale; items not in stock are produced to order.",
            "validUntilSentence", "This quotation is valid until %s.",
            "termsSentence", "All our quotations are subject to our general terms and conditions.",
            "mailSubject", "Quotation %s from Enrosed",
            "mailSubjectTermsAdded", "Delivery date confirmed - quotation %s from Enrosed",
            "mailGreeting", "Dear",
            "mailIntro", "Please find our quotation %s attached.",
            "mailIntroUpdated", "Please find the amended quotation %s attached.",
            "mailTermsAddedTitle", "Delivery date confirmed",
            "mailTermsAddedText", "You selected items that were not in stock. We have checked them"
                    + " and set a delivery date or week for each one. You will find them below and"
                    + " on the attached quotation, which you can now sign.",
            "mailDeliveryTitle", "Delivery",
            "mailDeliveryPerItem", "Delivery per item",
            "mailDeliveryPending", "For the items without a date we will contact you as soon as we"
                    + " can confirm one. You will then receive an amended quotation.",
            "mailFreightPending", "Freight charges are still to be determined. As soon as they are"
                    + " settled we will send you an amended quotation.",
            "mailButton", "View quotation online",
            "mailClosing", "Kind regards",
            "portalYourQuote", "Your quotation",
            "portalStatusOpen", "Awaiting your response",
            "portalStatusAccepted", "Accepted",
            "portalStatusRejected", "Declined",
            "portalStatusRevision", "Change under review",
            "portalAccept", "Accept and sign",
            "portalPropose", "Propose a change",
            "portalReject", "Decline",
            "portalDownload", "Download PDF",
            "portalDeliverableFrom", "Available from",
            "portalDeliveryInWeek", "Delivery in",
            "portalTermToBeDetermined", "Delivery date to be determined",
            "portalPerPiece", "per piece",
            "portalPieces", "pieces",
            "portalTermsAddedTitle", "Delivery date added",
            "portalTermsAddedText", "We have set a delivery date or week for every item. You will"
                    + " find them per line below; after that you can sign.",
            "portalTermsPendingTitle", "One or more items are not in stock",
            "portalTermsPendingText", "We are checking when we can deliver them and will send you"
                    + " this quotation again with the delivery date. You do not need to do anything"
                    + " now; you may of course already sign or propose a change.",
            "portalFreightPendingTitle", "Freight charges still to be determined",
            "portalFreightPendingText", "The amount below is still excluding freight. As soon as we"
                    + " know the charges we will send you an amended quotation.",
            "portalAddItem", "Add an item",
            "portalSearch", "Search our range…",
            "portalYourName", "Your name",
            "portalComment", "Comment",
            "portalCancel", "Cancel",
            "portalSend", "Send proposal",
            "portalAdd", "Add",
            "portalInStock", "in stock",
            "portalOutOfStock", "made to order",
            "portalPerBox", "per carton",
            "portalRoundingNotice", "Will be rounded to",
            "catalogCarton", "master carton",
            "catalogTitle", "Product catalogue",
            "catalogItems", "item(s)",
            "catalogFooter", "Prices per piece in euro, excluding VAT and freight, subject to change. Items not in stock are produced to order; the delivery time is agreed together.",
            "portalTerms", "Terms and conditions",
            "portalProposalPending", "Your proposed change is with us",
            "portalProposalPendingText", "We are reviewing your proposal and will send back an amended quotation. You can withdraw your proposal as long as we have not handled it.",
            "portalWithdraw", "Withdraw proposal",
            "portalWithdrawn", "Your proposal has been withdrawn. You can sign the quotation again or make a new proposal.",
            "portalNotFound", "Quotation not found",
            "portalNotFoundText", "This link is no longer valid. Do get in touch and we will send you a new one.",
            "portalFor", "for",
            "portalValidUntil", "valid until",
            "portalBoxes", "cartons",
            "portalPalletsShort", "pallet(s)",
            "portalDiscount", "discount",
            "portalSignedBy", "Signed by",
            "portalSignedText", "We will be in touch to confirm.",
            "portalProposalSent", "Your proposed change has been sent.",
            "portalProposalApproved", "Your change has been applied.",
            "portalProposalRejected", "Your proposal was not adopted.",
            "portalWhatNext", "What would you like to do?",
            "portalRejectQuote", "Decline quotation",
            "portalPdf", "Quotation as PDF",
            "portalLoading", "Loading…",
            "portalSignTitle", "Accept and sign",
            "portalSignText", "By entering your name you accept this quotation. That counts as your digital signature.",
            "portalSignButton", "Sign",
            "portalNoteOptional", "Note (optional)",
            "portalProposeText", "Adjust the quantities you would like to change. We review your proposal and send back an amended quotation. Set a quantity to 0 to drop a line.",
            "portalOnYourQuote", "On your quotation",
            "portalAddSection", "Add items",
            "portalOutOfStockWarning", "You selected an item that is not in stock. We need to accept that first and will let you know the delivery date; you will then receive an amended quotation to sign.",
            "portalReasonOptional", "Reason (optional)",
            "portalOptional", "optional",
            "portalFooter", "Questions? Simply reply to the email that came with this quotation.");

    private static final Map<String, String> DE_TEXT = bundle(
            "quote", "Angebot",
            "date", "Datum",
            "validUntil", "Gültig bis",
            "incoterm", "Incoterm",
            "customer", "Kunde",
            "noCustomer", "Kein Kunde verknüpft",
            "vat", "MwSt.",
            "vatNumber", "USt-IdNr.",
            "buyerVatNumber", "USt-IdNr. des Erwerbers",
            "delivery", "Lieferung",
            "destination", "Bestimmungsland",
            "pallets", "Paletten",
            "cartons", "Kartons",
            "payment", "Zahlung",
            "sku", "Art.-Nr.",
            "description", "Bezeichnung",
            "quantity", "Menge",
            "unitPrice", "Stückpreis",
            "discount", "Rabatt",
            "total", "Gesamt",
            "subtotal", "Zwischensumme",
            "orderDiscount", "Auftragsrabatt",
            "extraDiscount", "Zusatzrabatt",
            "goodsValue", "Warenwert",
            "freight", "Fracht",
            "handling", "Bearbeitung",
            "totalInclVat", "Gesamt inkl. MwSt.",
            "note", "Anmerkung",
            "from", "ab",
            "week", "KW",
            "toBeAgreed", "nach Absprache",
            "freightToBeDetermined", "wird noch ermittelt",
            "portalTitle", "Online ansehen, unterzeichnen oder ändern",
            "portalText", "Dieses Angebot steht auch online zur Verfügung. Dort können Sie es"
                    + " annehmen und digital unterzeichnen oder eine Änderung vorschlagen, die wir"
                    + " prüfen.",
            "footer", "Alle Preise in Euro. Liefertermine gelten ab Bestätigung und unter Vorbehalt"
                    + " des Zwischenverkaufs; nicht vorrätige Artikel werden auf Bestellung"
                    + " gefertigt.",
            "validUntilSentence", "Dieses Angebot ist gültig bis %s.",
            "termsSentence", "Für alle unsere Angebote gelten unsere Allgemeinen"
                    + " Geschäftsbedingungen.",
            "mailSubject", "Angebot %s von Enrosed",
            "mailSubjectTermsAdded", "Liefertermin ergänzt - Angebot %s von Enrosed",
            "mailGreeting", "Guten Tag",
            "mailIntro", "Anbei finden Sie unser Angebot %s.",
            "mailIntroUpdated", "Anbei finden Sie das angepasste Angebot %s.",
            "mailTermsAddedTitle", "Liefertermin ergänzt",
            "mailTermsAddedText", "Sie hatten Artikel gewählt, die nicht vorrätig waren. Wir haben"
                    + " sie geprüft und für jeden Artikel ein Lieferdatum oder eine Lieferwoche"
                    + " festgelegt. Sie finden diese unten und auf dem beigefügten Angebot, das Sie"
                    + " nun unterzeichnen können.",
            "mailDeliveryTitle", "Liefertermin",
            "mailDeliveryPerItem", "Liefertermin je Artikel",
            "mailDeliveryPending", "Für die Artikel ohne Termin melden wir uns, sobald wir diesen"
                    + " bestätigen können. Sie erhalten dann ein angepasstes Angebot.",
            "mailFreightPending", "Die Frachtkosten werden noch ermittelt. Sobald sie feststehen,"
                    + " senden wir Ihnen ein angepasstes Angebot.",
            "mailButton", "Angebot online ansehen",
            "mailClosing", "Mit freundlichen Grüßen",
            "portalYourQuote", "Ihr Angebot",
            "portalStatusOpen", "Zur Prüfung",
            "portalStatusAccepted", "Angenommen",
            "portalStatusRejected", "Abgelehnt",
            "portalStatusRevision", "Änderung in Bearbeitung",
            "portalAccept", "Annehmen und unterzeichnen",
            "portalPropose", "Änderung vorschlagen",
            "portalReject", "Ablehnen",
            "portalDownload", "PDF herunterladen",
            "portalDeliverableFrom", "Lieferbar ab",
            "portalDeliveryInWeek", "Lieferung in",
            "portalTermToBeDetermined", "Liefertermin noch offen",
            "portalPerPiece", "pro Stück",
            "portalPieces", "Stück",
            "portalTermsAddedTitle", "Liefertermin ergänzt",
            "portalTermsAddedText", "Wir haben für jeden Artikel ein Lieferdatum oder eine"
                    + " Lieferwoche festgelegt. Sie finden diese unten je Position; danach können"
                    + " Sie unterzeichnen.",
            "portalTermsPendingTitle", "Ein oder mehrere Artikel sind nicht vorrätig",
            "portalTermsPendingText", "Wir prüfen, wann wir liefern können, und senden Ihnen dieses"
                    + " Angebot erneut mit dem Liefertermin. Sie müssen jetzt nichts tun; Sie können"
                    + " selbstverständlich bereits unterzeichnen oder eine Änderung vorschlagen.",
            "portalFreightPendingTitle", "Frachtkosten werden noch ermittelt",
            "portalFreightPendingText", "Der Betrag unten versteht sich noch ohne Fracht. Sobald wir"
                    + " die Kosten kennen, senden wir Ihnen ein angepasstes Angebot.",
            "portalAddItem", "Artikel hinzufügen",
            "portalSearch", "In unserem Sortiment suchen…",
            "portalYourName", "Ihr Name",
            "portalComment", "Anmerkung",
            "portalCancel", "Abbrechen",
            "portalSend", "Vorschlag senden",
            "portalAdd", "Hinzufügen",
            "portalInStock", "auf Lager",
            "portalOutOfStock", "auf Bestellung",
            "portalPerBox", "pro Karton",
            "portalRoundingNotice", "Wird aufgerundet auf",
            "catalogCarton", "Umkarton",
            "catalogTitle", "Produktkatalog",
            "catalogItems", "Artikel",
            "catalogFooter", "Stückpreise in Euro, zuzüglich MwSt. und Fracht, Änderungen vorbehalten. Nicht vorrätige Artikel werden auf Bestellung gefertigt; den Liefertermin stimmen wir gemeinsam ab.",
            "portalTerms", "Allgemeine Geschäftsbedingungen",
            "portalProposalPending", "Ihr Änderungsvorschlag liegt bei uns",
            "portalProposalPendingText", "Wir prüfen Ihren Vorschlag und senden Ihnen ein angepasstes Angebot zurück. Sie können Ihren Vorschlag zurückziehen, solange wir ihn nicht bearbeitet haben.",
            "portalWithdraw", "Vorschlag zurückziehen",
            "portalWithdrawn", "Ihr Vorschlag wurde zurückgezogen. Sie können das Angebot wieder unterzeichnen oder einen neuen Vorschlag machen.",
            "portalNotFound", "Angebot nicht gefunden",
            "portalNotFoundText", "Dieser Link ist nicht mehr gültig. Melden Sie sich gerne, dann senden wir Ihnen einen neuen.",
            "portalFor", "für",
            "portalValidUntil", "gültig bis",
            "portalBoxes", "Kartons",
            "portalPalletsShort", "Palette(n)",
            "portalDiscount", "Rabatt",
            "portalSignedBy", "Unterzeichnet von",
            "portalSignedText", "Wir melden uns zur Bestätigung.",
            "portalProposalSent", "Ihr Änderungsvorschlag wurde übermittelt.",
            "portalProposalApproved", "Ihre Änderung wurde übernommen.",
            "portalProposalRejected", "Ihr Vorschlag wurde nicht übernommen.",
            "portalWhatNext", "Was möchten Sie tun?",
            "portalRejectQuote", "Angebot ablehnen",
            "portalPdf", "Angebot als PDF",
            "portalLoading", "Wird geladen…",
            "portalSignTitle", "Annehmen und unterzeichnen",
            "portalSignText", "Mit der Eingabe Ihres Namens nehmen Sie dieses Angebot an. Das gilt als Ihre digitale Unterschrift.",
            "portalSignButton", "Unterzeichnen",
            "portalNoteOptional", "Anmerkung (optional)",
            "portalProposeText", "Passen Sie die Mengen an, die Sie ändern möchten. Wir prüfen Ihren Vorschlag und senden Ihnen ein angepasstes Angebot zurück. Setzen Sie eine Menge auf 0, um eine Position zu streichen.",
            "portalOnYourQuote", "Auf Ihrem Angebot",
            "portalAddSection", "Artikel hinzufügen",
            "portalOutOfStockWarning", "Sie haben einen Artikel gewählt, der nicht vorrätig ist. Wir müssen das zuerst annehmen und teilen Ihnen den Liefertermin mit; danach erhalten Sie ein angepasstes Angebot zur Unterzeichnung.",
            "portalReasonOptional", "Grund (optional)",
            "portalOptional", "optional",
            "portalFooter", "Fragen? Antworten Sie einfach auf die E-Mail mit diesem Angebot.");
    private static final Map<String, String> ES_TEXT = bundle(
            "quote", "Presupuesto",
            "date", "Fecha",
            "validUntil", "Válido hasta",
            "incoterm", "Incoterm",
            "customer", "Cliente",
            "noCustomer", "Ningún cliente vinculado",
            "vat", "IVA",
            "vatNumber", "NIF-IVA",
            "buyerVatNumber", "NIF-IVA del comprador",
            "delivery", "Entrega",
            "destination", "Destino",
            "pallets", "Palés",
            "cartons", "Cajas",
            "payment", "Pago",
            "sku", "Ref.",
            "description", "Descripción",
            "quantity", "Cantidad",
            "unitPrice", "Precio unitario",
            "discount", "Descuento",
            "total", "Total",
            "subtotal", "Subtotal",
            "orderDiscount", "Descuento por pedido",
            "extraDiscount", "Descuento adicional",
            "goodsValue", "Valor de la mercancía",
            "freight", "Transporte",
            "handling", "Gastos de gestión",
            "totalInclVat", "Total con IVA",
            "note", "Observación",
            "from", "a partir del",
            "week", "semana",
            "toBeAgreed", "a convenir",
            "freightToBeDetermined", "por determinar",
            "portalTitle", "Consultar, firmar o modificar en línea",
            "portalText", "Este presupuesto también está disponible en línea. Allí puede"
                    + " aceptarlo y firmarlo digitalmente, o proponer un cambio que"
                    + " revisaremos.",
            "footer", "Todos los precios en euros. Los plazos de entrega rigen desde la"
                    + " confirmación y salvo venta previa; los artículos sin existencias se"
                    + " producen bajo pedido.",
            "validUntilSentence", "Este presupuesto es válido hasta el %s.",
            "termsSentence", "Todos nuestros presupuestos están sujetos a nuestras condiciones"
                    + " generales.",
            "mailSubject", "Presupuesto %s de Enrosed",
            "mailSubjectTermsAdded", "Plazo de entrega confirmado - presupuesto %s de Enrosed",
            "mailGreeting", "Estimado/a",
            "mailIntro", "Adjunto encontrará nuestro presupuesto %s.",
            "mailIntroUpdated", "Adjunto encontrará el presupuesto %s actualizado.",
            "mailTermsAddedTitle", "Plazo de entrega confirmado",
            "mailTermsAddedText", "Eligió artículos que no estaban en stock. Los hemos revisado y"
                    + " fijado una fecha o semana de entrega para cada uno. Los encontrará a"
                    + " continuación y en el presupuesto adjunto, que ya puede firmar.",
            "mailDeliveryTitle", "Plazo de entrega",
            "mailDeliveryPerItem", "Plazo de entrega por artículo",
            "mailDeliveryPending", "Para los artículos sin plazo le contactaremos en cuanto podamos"
                    + " confirmarlo. Recibirá entonces un presupuesto actualizado.",
            "mailFreightPending", "Los gastos de transporte están por determinar. En cuanto se fijen le"
                    + " enviaremos un presupuesto actualizado.",
            "mailButton", "Ver el presupuesto en línea",
            "mailClosing", "Un cordial saludo",
            "portalYourQuote", "Su presupuesto",
            "portalStatusOpen", "Pendiente de respuesta",
            "portalStatusAccepted", "Aceptado",
            "portalStatusRejected", "Rechazado",
            "portalStatusRevision", "Cambio en revisión",
            "portalAccept", "Aceptar y firmar",
            "portalPropose", "Proponer un cambio",
            "portalReject", "Rechazar",
            "portalDownload", "Descargar PDF",
            "portalDeliverableFrom", "Disponible a partir del",
            "portalDeliveryInWeek", "Entrega en",
            "portalTermToBeDetermined", "Plazo de entrega por determinar",
            "portalPerPiece", "por unidad",
            "portalPieces", "unidades",
            "portalTermsAddedTitle", "Plazo de entrega añadido",
            "portalTermsAddedText", "Hemos fijado una fecha o semana de entrega para cada artículo. Los"
                    + " encontrará por línea a continuación; después puede firmar.",
            "portalTermsPendingTitle", "Uno o más artículos no están en stock",
            "portalTermsPendingText", "Estamos comprobando cuándo podemos entregarlos y le enviaremos este"
                    + " presupuesto de nuevo con el plazo de entrega. No tiene que hacer"
                    + " nada por ahora; por supuesto puede firmar ya o proponer un cambio.",
            "portalFreightPendingTitle", "Gastos de transporte por determinar",
            "portalFreightPendingText", "El importe siguiente aún no incluye el transporte. En cuanto"
                    + " conozcamos los gastos le enviaremos un presupuesto actualizado.",
            "portalAddItem", "Añadir un artículo",
            "portalSearch", "Buscar en nuestra gama…",
            "portalYourName", "Su nombre",
            "portalComment", "Comentario",
            "portalCancel", "Cancelar",
            "portalSend", "Enviar la propuesta",
            "portalAdd", "Añadir",
            "portalInStock", "en stock",
            "portalOutOfStock", "bajo pedido",
            "portalPerBox", "por caja",
            "portalRoundingNotice", "Se redondeará a",
            "catalogCarton", "caja máster",
            "catalogTitle", "Catálogo de productos",
            "catalogItems", "artículo(s)",
            "catalogFooter", "Precios por unidad en euros, sin IVA ni transporte, sujetos a cambios. Los artículos sin stock se producen bajo pedido; el plazo de entrega se acuerda conjuntamente.",
            "portalTerms", "Condiciones generales",
            "portalProposalPending", "Su propuesta está con nosotros",
            "portalProposalPendingText", "Estamos revisando su propuesta y le devolveremos un presupuesto actualizado. Puede retirar su propuesta mientras no la hayamos tramitado.",
            "portalWithdraw", "Retirar la propuesta",
            "portalWithdrawn", "Su propuesta ha sido retirada. Puede volver a firmar el presupuesto o hacer una nueva propuesta.",
            "portalNotFound", "Presupuesto no encontrado",
            "portalNotFoundText", "Este enlace ya no es válido. Póngase en contacto con nosotros y le"
                    + " enviaremos uno nuevo.",
            "portalFor", "para",
            "portalValidUntil", "válido hasta",
            "portalBoxes", "cajas",
            "portalPalletsShort", "palé(s)",
            "portalDiscount", "descuento",
            "portalSignedBy", "Firmado por",
            "portalSignedText", "Nos pondremos en contacto para la confirmación.",
            "portalProposalSent", "Su propuesta de cambio ha sido enviada.",
            "portalProposalApproved", "Su cambio ha sido aplicado.",
            "portalProposalRejected", "Su propuesta no ha sido aceptada.",
            "portalWhatNext", "¿Qué desea hacer?",
            "portalRejectQuote", "Rechazar el presupuesto",
            "portalPdf", "Presupuesto en PDF",
            "portalLoading", "Cargando…",
            "portalSignTitle", "Aceptar y firmar",
            "portalSignText", "Al indicar su nombre acepta este presupuesto. Esto vale como su"
                    + " firma digital.",
            "portalSignButton", "Firmar",
            "portalNoteOptional", "Observación (opcional)",
            "portalProposeText", "Ajuste las cantidades que desee cambiar. Revisamos su propuesta y le"
                    + " devolvemos un presupuesto actualizado. Ponga una cantidad a 0 para"
                    + " eliminar una línea.",
            "portalOnYourQuote", "En su presupuesto",
            "portalAddSection", "Añadir artículos",
            "portalOutOfStockWarning", "Ha elegido un artículo que no está en stock. Primero tenemos que"
                    + " aceptarlo y le comunicaremos el plazo de entrega; después recibirá"
                    + " un presupuesto actualizado para firmar.",
            "portalReasonOptional", "Motivo (opcional)",
            "portalOptional", "opcional",
            "portalFooter", "¿Preguntas? Responda sin más al correo que acompaña este presupuesto.");

    private static final Map<String, String> PL_TEXT = bundle(
            "quote", "Oferta",
            "date", "Data",
            "validUntil", "Ważna do",
            "incoterm", "Incoterm",
            "customer", "Klient",
            "noCustomer", "Brak powiązanego klienta",
            "vat", "VAT",
            "vatNumber", "NIP",
            "buyerVatNumber", "NIP nabywcy",
            "delivery", "Dostawa",
            "destination", "Miejsce przeznaczenia",
            "pallets", "Palety",
            "cartons", "Kartony",
            "payment", "Płatność",
            "sku", "Nr kat.",
            "description", "Opis",
            "quantity", "Ilość",
            "unitPrice", "Cena jednostkowa",
            "discount", "Rabat",
            "total", "Razem",
            "subtotal", "Suma częściowa",
            "orderDiscount", "Rabat na zamówienie",
            "extraDiscount", "Rabat dodatkowy",
            "goodsValue", "Wartość towaru",
            "freight", "Transport",
            "handling", "Obsługa",
            "totalInclVat", "Razem z VAT",
            "note", "Uwaga",
            "from", "od",
            "week", "tydzień",
            "toBeAgreed", "do uzgodnienia",
            "freightToBeDetermined", "do ustalenia",
            "portalTitle", "Podgląd, podpis lub zmiana online",
            "portalText", "Ta oferta jest dostępna również online. Można ją tam zaakceptować i"
                    + " podpisać cyfrowo albo zaproponować zmianę, którą sprawdzimy.",
            "footer", "Wszystkie ceny w euro. Terminy dostawy obowiązują od potwierdzenia i"
                    + " z zastrzeżeniem wcześniejszej sprzedaży; artykuły niedostępne w"
                    + " magazynie produkowane są na zamówienie.",
            "validUntilSentence", "Niniejsza oferta jest ważna do %s.",
            "termsSentence", "Wszystkie nasze oferty podlegają naszym ogólnym warunkom handlowym.",
            "mailSubject", "Oferta %s od Enrosed",
            "mailSubjectTermsAdded", "Termin dostawy ustalony - oferta %s od Enrosed",
            "mailGreeting", "Szanowni Państwo",
            "mailIntro", "W załączeniu przesyłamy naszą ofertę %s.",
            "mailIntroUpdated", "W załączeniu przesyłamy zaktualizowaną ofertę %s.",
            "mailTermsAddedTitle", "Termin dostawy ustalony",
            "mailTermsAddedText", "Wybrali Państwo artykuły niedostępne w magazynie. Sprawdziliśmy je i"
                    + " ustaliliśmy dla każdego datę lub tydzień dostawy. Znajdą je Państwo"
                    + " poniżej oraz w załączonej ofercie, którą można teraz podpisać.",
            "mailDeliveryTitle", "Termin dostawy",
            "mailDeliveryPerItem", "Termin dostawy dla każdego artykułu",
            "mailDeliveryPending", "W sprawie artykułów bez terminu skontaktujemy się, gdy tylko"
                    + " będziemy mogli go potwierdzić. Otrzymają Państwo wtedy"
                    + " zaktualizowaną ofertę.",
            "mailFreightPending", "Koszty transportu są jeszcze do ustalenia. Gdy tylko zostaną"
                    + " ustalone, prześlemy Państwu zaktualizowaną ofertę.",
            "mailButton", "Zobacz ofertę online",
            "mailClosing", "Z poważaniem",
            "portalYourQuote", "Państwa oferta",
            "portalStatusOpen", "Oczekuje na odpowiedź",
            "portalStatusAccepted", "Zaakceptowana",
            "portalStatusRejected", "Odrzucona",
            "portalStatusRevision", "Zmiana w trakcie rozpatrywania",
            "portalAccept", "Akceptuj i podpisz",
            "portalPropose", "Zaproponuj zmianę",
            "portalReject", "Odrzuć",
            "portalDownload", "Pobierz PDF",
            "portalDeliverableFrom", "Dostępne od",
            "portalDeliveryInWeek", "Dostawa w",
            "portalTermToBeDetermined", "Termin dostawy do ustalenia",
            "portalPerPiece", "za sztukę",
            "portalPieces", "szt.",
            "portalTermsAddedTitle", "Termin dostawy dodany",
            "portalTermsAddedText", "Ustaliliśmy datę lub tydzień dostawy dla każdego artykułu. Znajdą je"
                    + " Państwo poniżej przy każdej pozycji; następnie można podpisać.",
            "portalTermsPendingTitle", "Jeden lub więcej artykułów nie jest dostępnych w magazynie",
            "portalTermsPendingText", "Sprawdzamy, kiedy możemy je dostarczyć, i prześlemy tę ofertę"
                    + " ponownie wraz z terminem dostawy. Na razie nie trzeba nic robić;"
                    + " oczywiście można już podpisać lub zaproponować zmianę.",
            "portalFreightPendingTitle", "Koszty transportu do ustalenia",
            "portalFreightPendingText", "Poniższa kwota nie zawiera jeszcze transportu. Gdy poznamy koszty,"
                    + " prześlemy Państwu zaktualizowaną ofertę.",
            "portalAddItem", "Dodaj artykuł",
            "portalSearch", "Szukaj w naszym asortymencie…",
            "portalYourName", "Imię i nazwisko",
            "portalComment", "Uwagi",
            "portalCancel", "Anuluj",
            "portalSend", "Wyślij propozycję",
            "portalAdd", "Dodaj",
            "portalInStock", "w magazynie",
            "portalOutOfStock", "na zamówienie",
            "portalPerBox", "w kartonie",
            "portalRoundingNotice", "Zostanie zaokrąglone do",
            "catalogCarton", "karton zbiorczy",
            "catalogTitle", "Katalog produktów",
            "catalogItems", "artykuł(y)",
            "catalogFooter", "Ceny za sztukę w euro, bez VAT i transportu, mogą ulec zmianie. Artykuły niedostępne w magazynie produkowane są na zamówienie; termin dostawy ustalamy wspólnie.",
            "portalTerms", "Ogólne warunki",
            "portalProposalPending", "Państwa propozycja jest u nas",
            "portalProposalPendingText", "Sprawdzamy Państwa propozycję i odeślemy zaktualizowaną ofertę. Propozycję można wycofać, dopóki jej nie rozpatrzymy.",
            "portalWithdraw", "Wycofaj propozycję",
            "portalWithdrawn", "Państwa propozycja została wycofana. Można ponownie podpisać ofertę lub złożyć nową propozycję.",
            "portalNotFound", "Nie znaleziono oferty",
            "portalNotFoundText", "Ten link nie jest już aktywny. Prosimy o kontakt, prześlemy nowy.",
            "portalFor", "dla",
            "portalValidUntil", "ważna do",
            "portalBoxes", "kartony",
            "portalPalletsShort", "paleta(y)",
            "portalDiscount", "rabat",
            "portalSignedBy", "Podpisano przez",
            "portalSignedText", "Skontaktujemy się w celu potwierdzenia.",
            "portalProposalSent", "Państwa propozycja zmiany została przesłana.",
            "portalProposalApproved", "Państwa zmiana została wprowadzona.",
            "portalProposalRejected", "Państwa propozycja nie została przyjęta.",
            "portalWhatNext", "Co chcą Państwo zrobić?",
            "portalRejectQuote", "Odrzuć ofertę",
            "portalPdf", "Oferta w PDF",
            "portalLoading", "Wczytywanie…",
            "portalSignTitle", "Akceptuj i podpisz",
            "portalSignText", "Wpisując swoje imię i nazwisko, akceptują Państwo tę ofertę. Jest to"
                    + " równoznaczne z podpisem cyfrowym.",
            "portalSignButton", "Podpisz",
            "portalNoteOptional", "Uwaga (opcjonalnie)",
            "portalProposeText", "Prosimy dostosować ilości, które chcą Państwo zmienić. Sprawdzimy"
                    + " propozycję i odeślemy zaktualizowaną ofertę. Ustawienie ilości na 0"
                    + " usuwa pozycję.",
            "portalOnYourQuote", "W Państwa ofercie",
            "portalAddSection", "Dodaj artykuły",
            "portalOutOfStockWarning", "Wybrali Państwo artykuł niedostępny w magazynie. Musimy to najpierw"
                    + " zaakceptować i podamy termin dostawy; następnie otrzymają Państwo"
                    + " zaktualizowaną ofertę do podpisu.",
            "portalReasonOptional", "Powód (opcjonalnie)",
            "portalOptional", "opcjonalnie",
            "portalFooter", "Pytania? Wystarczy odpowiedzieć na wiadomość e-mail z tą ofertą.");

    private static final Map<String, String> PT_TEXT = bundle(
            "quote", "Orçamento",
            "date", "Data",
            "validUntil", "Válido até",
            "incoterm", "Incoterm",
            "customer", "Cliente",
            "noCustomer", "Nenhum cliente associado",
            "vat", "IVA",
            "vatNumber", "NIF",
            "buyerVatNumber", "NIF do adquirente",
            "delivery", "Entrega",
            "destination", "Destino",
            "pallets", "Paletes",
            "cartons", "Caixas",
            "payment", "Pagamento",
            "sku", "Ref.",
            "description", "Descrição",
            "quantity", "Quantidade",
            "unitPrice", "Preço unitário",
            "discount", "Desconto",
            "total", "Total",
            "subtotal", "Subtotal",
            "orderDiscount", "Desconto na encomenda",
            "extraDiscount", "Desconto adicional",
            "goodsValue", "Valor da mercadoria",
            "freight", "Transporte",
            "handling", "Encargos administrativos",
            "totalInclVat", "Total com IVA",
            "note", "Observação",
            "from", "a partir de",
            "week", "semana",
            "toBeAgreed", "a combinar",
            "freightToBeDetermined", "a determinar",
            "portalTitle", "Consultar, assinar ou alterar online",
            "portalText", "Este orçamento está também disponível online. Aí pode aceitá-lo e"
                    + " assiná-lo digitalmente, ou propor uma alteração que iremos analisar.",
            "footer", "Todos os preços em euros. Os prazos de entrega contam a partir da"
                    + " confirmação e salvo venda entretanto; os artigos sem stock são"
                    + " produzidos por encomenda.",
            "validUntilSentence", "Este orçamento é válido até %s.",
            "termsSentence", "Todos os nossos orçamentos estão sujeitos às nossas condições gerais.",
            "mailSubject", "Orçamento %s da Enrosed",
            "mailSubjectTermsAdded", "Prazo de entrega confirmado - orçamento %s da Enrosed",
            "mailGreeting", "Caro(a)",
            "mailIntro", "Em anexo encontra o nosso orçamento %s.",
            "mailIntroUpdated", "Em anexo encontra o orçamento %s atualizado.",
            "mailTermsAddedTitle", "Prazo de entrega confirmado",
            "mailTermsAddedText", "Escolheu artigos que não estavam em stock. Verificámo-los e"
                    + " definimos uma data ou semana de entrega para cada um. Encontra-os"
                    + " abaixo e no orçamento em anexo, que já pode assinar.",
            "mailDeliveryTitle", "Prazo de entrega",
            "mailDeliveryPerItem", "Prazo de entrega por artigo",
            "mailDeliveryPending", "Para os artigos sem prazo entraremos em contacto assim que o"
                    + " possamos confirmar. Receberá então um orçamento atualizado.",
            "mailFreightPending", "Os custos de transporte estão ainda por determinar. Assim que"
                    + " estiverem definidos enviaremos um orçamento atualizado.",
            "mailButton", "Ver o orçamento online",
            "mailClosing", "Com os melhores cumprimentos",
            "portalYourQuote", "O seu orçamento",
            "portalStatusOpen", "A aguardar resposta",
            "portalStatusAccepted", "Aceite",
            "portalStatusRejected", "Recusado",
            "portalStatusRevision", "Alteração em análise",
            "portalAccept", "Aceitar e assinar",
            "portalPropose", "Propor uma alteração",
            "portalReject", "Recusar",
            "portalDownload", "Descarregar PDF",
            "portalDeliverableFrom", "Disponível a partir de",
            "portalDeliveryInWeek", "Entrega na",
            "portalTermToBeDetermined", "Prazo de entrega a determinar",
            "portalPerPiece", "por unidade",
            "portalPieces", "unidades",
            "portalTermsAddedTitle", "Prazo de entrega adicionado",
            "portalTermsAddedText", "Definimos uma data ou semana de entrega para cada artigo."
                    + " Encontra-os abaixo por linha; depois pode assinar.",
            "portalTermsPendingTitle", "Um ou mais artigos não estão em stock",
            "portalTermsPendingText", "Estamos a verificar quando os podemos entregar e enviaremos este"
                    + " orçamento novamente com o prazo de entrega. Não precisa de fazer"
                    + " nada por agora; pode naturalmente assinar já ou propor uma"
                    + " alteração.",
            "portalFreightPendingTitle", "Custos de transporte a determinar",
            "portalFreightPendingText", "O valor abaixo ainda não inclui transporte. Assim que soubermos os"
                    + " custos enviaremos um orçamento atualizado.",
            "portalAddItem", "Adicionar um artigo",
            "portalSearch", "Pesquisar na nossa gama…",
            "portalYourName", "O seu nome",
            "portalComment", "Comentário",
            "portalCancel", "Cancelar",
            "portalSend", "Enviar a proposta",
            "portalAdd", "Adicionar",
            "portalInStock", "em stock",
            "portalOutOfStock", "por encomenda",
            "portalPerBox", "por caixa",
            "portalRoundingNotice", "Será arredondado para",
            "catalogCarton", "caixa master",
            "catalogTitle", "Catálogo de produtos",
            "catalogItems", "artigo(s)",
            "catalogFooter", "Preços por unidade em euros, sem IVA nem transporte, sujeitos a alteração. Os artigos sem stock são produzidos por encomenda; o prazo de entrega é combinado em conjunto.",
            "portalTerms", "Condições gerais",
            "portalProposalPending", "A sua proposta está connosco",
            "portalProposalPendingText", "Estamos a analisar a sua proposta e devolveremos um orçamento atualizado. Pode retirar a sua proposta enquanto não a tivermos tratado.",
            "portalWithdraw", "Retirar a proposta",
            "portalWithdrawn", "A sua proposta foi retirada. Pode assinar novamente o orçamento ou fazer uma nova proposta.",
            "portalNotFound", "Orçamento não encontrado",
            "portalNotFoundText", "Esta ligação já não é válida. Contacte-nos e enviaremos uma nova.",
            "portalFor", "para",
            "portalValidUntil", "válido até",
            "portalBoxes", "caixas",
            "portalPalletsShort", "palete(s)",
            "portalDiscount", "desconto",
            "portalSignedBy", "Assinado por",
            "portalSignedText", "Entraremos em contacto para a confirmação.",
            "portalProposalSent", "A sua proposta de alteração foi enviada.",
            "portalProposalApproved", "A sua alteração foi aplicada.",
            "portalProposalRejected", "A sua proposta não foi aceite.",
            "portalWhatNext", "O que pretende fazer?",
            "portalRejectQuote", "Recusar o orçamento",
            "portalPdf", "Orçamento em PDF",
            "portalLoading", "A carregar…",
            "portalSignTitle", "Aceitar e assinar",
            "portalSignText", "Ao indicar o seu nome aceita este orçamento. Isso vale como a sua"
                    + " assinatura digital.",
            "portalSignButton", "Assinar",
            "portalNoteOptional", "Observação (opcional)",
            "portalProposeText", "Ajuste as quantidades que pretende alterar. Analisamos a sua"
                    + " proposta e devolvemos um orçamento atualizado. Coloque uma"
                    + " quantidade a 0 para eliminar uma linha.",
            "portalOnYourQuote", "No seu orçamento",
            "portalAddSection", "Adicionar artigos",
            "portalOutOfStockWarning", "Escolheu um artigo que não está em stock. Temos primeiro de o"
                    + " aceitar e comunicaremos o prazo de entrega; depois receberá um"
                    + " orçamento atualizado para assinar.",
            "portalReasonOptional", "Motivo (opcional)",
            "portalOptional", "opcional",
            "portalFooter", "Questões? Basta responder ao e-mail que acompanha este orçamento.");

    private static final Map<String, String> TR_TEXT = bundle(
            "quote", "Teklif",
            "date", "Tarih",
            "validUntil", "Geçerlilik tarihi",
            "incoterm", "Teslim şekli",
            "customer", "Müşteri",
            "noCustomer", "Bağlı müşteri yok",
            "vat", "KDV",
            "vatNumber", "Vergi numarası",
            "buyerVatNumber", "Alıcının vergi numarası",
            "delivery", "Teslimat",
            "destination", "Varış ülkesi",
            "pallets", "Palet",
            "cartons", "Koli",
            "payment", "Ödeme",
            "sku", "Ürün kodu",
            "description", "Açıklama",
            "quantity", "Adet",
            "unitPrice", "Birim fiyat",
            "discount", "İndirim",
            "total", "Toplam",
            "subtotal", "Ara toplam",
            "orderDiscount", "Sipariş indirimi",
            "extraDiscount", "Ek indirim",
            "goodsValue", "Mal bedeli",
            "freight", "Navlun",
            "handling", "İşlem ücreti",
            "totalInclVat", "KDV dahil toplam",
            "note", "Not",
            "from", "itibaren",
            "week", "hafta",
            "toBeAgreed", "görüşülecek",
            "freightToBeDetermined", "belirlenecek",
            "portalTitle", "Çevrimiçi görüntüleyin, imzalayın veya değiştirin",
            "portalText", "Bu teklif çevrimiçi olarak da mevcuttur. Orada teklifi kabul edip"
                    + " dijital olarak imzalayabilir veya inceleyeceğimiz bir değişiklik"
                    + " önerebilirsiniz.",
            "footer", "Tüm fiyatlar euro cinsindendir. Teslim tarihleri onaydan itibaren"
                    + " geçerlidir ve ara satış hakkı saklıdır; stokta bulunmayan ürünler"
                    + " siparişe göre üretilir.",
            "validUntilSentence", "Bu teklif %s tarihine kadar geçerlidir.",
            "termsSentence", "Tüm tekliflerimiz genel şartlarımıza tabidir.",
            "mailSubject", "Enrosed %s numaralı teklif",
            "mailSubjectTermsAdded", "Teslim süresi belirlendi - Enrosed %s numaralı teklif",
            "mailGreeting", "Sayın yetkili",
            "mailIntro", "Ekte %s numaralı teklifimizi bulabilirsiniz.",
            "mailIntroUpdated", "Ekte güncellenmiş %s numaralı teklifi bulabilirsiniz.",
            "mailTermsAddedTitle", "Teslim süresi belirlendi",
            "mailTermsAddedText", "Stokta bulunmayan ürünler seçmiştiniz. Bunları inceledik ve her ürün"
                    + " için bir teslim tarihi veya haftası belirledik. Aşağıda ve ekteki"
                    + " teklifte yer alıyorlar; teklifi artık imzalayabilirsiniz.",
            "mailDeliveryTitle", "Teslim süresi",
            "mailDeliveryPerItem", "Ürün bazında teslim süresi",
            "mailDeliveryPending", "Teslim süresi belirlenmemiş ürünler için, süreyi teyit edebildiğimiz"
                    + " anda sizinle iletişime geçeceğiz. Ardından güncellenmiş bir teklif"
                    + " alacaksınız.",
            "mailFreightPending", "Navlun bedeli henüz belirlenmedi. Netleştiği anda size güncellenmiş"
                    + " bir teklif göndereceğiz.",
            "mailButton", "Teklifi çevrimiçi görüntüle",
            "mailClosing", "Saygılarımızla",
            "portalYourQuote", "Teklifiniz",
            "portalStatusOpen", "Yanıtınız bekleniyor",
            "portalStatusAccepted", "Kabul edildi",
            "portalStatusRejected", "Reddedildi",
            "portalStatusRevision", "Değişiklik inceleniyor",
            "portalAccept", "Kabul et ve imzala",
            "portalPropose", "Değişiklik öner",
            "portalReject", "Reddet",
            "portalDownload", "PDF indir",
            "portalDeliverableFrom", "Şu tarihten itibaren teslim edilebilir",
            "portalDeliveryInWeek", "Teslimat",
            "portalTermToBeDetermined", "Teslim süresi belirlenecek",
            "portalPerPiece", "adet başına",
            "portalPieces", "adet",
            "portalTermsAddedTitle", "Teslim süresi eklendi",
            "portalTermsAddedText", "Her ürün için bir teslim tarihi veya haftası belirledik. Aşağıda"
                    + " satır satır görebilirsiniz; ardından imzalayabilirsiniz.",
            "portalTermsPendingTitle", "Bir veya daha fazla ürün stokta yok",
            "portalTermsPendingText", "Bunları ne zaman teslim edebileceğimizi inceliyoruz ve bu teklifi"
                    + " teslim süresiyle birlikte size yeniden göndereceğiz. Şimdilik bir"
                    + " şey yapmanıza gerek yok; dilerseniz şimdiden imzalayabilir veya"
                    + " değişiklik önerebilirsiniz.",
            "portalFreightPendingTitle", "Navlun bedeli henüz belirlenmedi",
            "portalFreightPendingText", "Aşağıdaki tutara navlun dahil değildir. Bedeli öğrendiğimiz anda"
                    + " size güncellenmiş bir teklif göndereceğiz.",
            "portalAddItem", "Ürün ekle",
            "portalSearch", "Ürün gamımızda arayın…",
            "portalYourName", "Adınız",
            "portalComment", "Açıklama",
            "portalCancel", "İptal",
            "portalSend", "Öneriyi gönder",
            "portalAdd", "Ekle",
            "portalInStock", "stokta",
            "portalOutOfStock", "siparişe göre",
            "portalPerBox", "koli başına",
            "portalRoundingNotice", "Şu değere yuvarlanacak:",
            "catalogCarton", "ana koli",
            "catalogTitle", "Ürün kataloğu",
            "catalogItems", "ürün",
            "catalogFooter", "Birim fiyatlar euro cinsinden olup KDV ve navlun hariçtir; değişiklik hakkı saklıdır. Stokta olmayan ürünler siparişe göre üretilir; teslim süresi birlikte kararlaştırılır.",
            "portalTerms", "Genel şartlar",
            "portalProposalPending", "Öneriniz bizde",
            "portalProposalPendingText", "Önerinizi inceliyoruz ve size güncellenmiş bir teklif göndereceğiz. Biz işleme almadığımız sürece önerinizi geri çekebilirsiniz.",
            "portalWithdraw", "Öneriyi geri çek",
            "portalWithdrawn", "Öneriniz geri çekildi. Teklifi tekrar imzalayabilir veya yeni bir öneri yapabilirsiniz.",
            "portalNotFound", "Teklif bulunamadı",
            "portalNotFoundText", "Bu bağlantı artık geçerli değil. Bizimle iletişime geçin, size"
                    + " yenisini gönderelim.",
            "portalFor", "için",
            "portalValidUntil", "geçerlilik tarihi",
            "portalBoxes", "koli",
            "portalPalletsShort", "palet",
            "portalDiscount", "indirim",
            "portalSignedBy", "İmzalayan",
            "portalSignedText", "Onay için sizinle iletişime geçeceğiz.",
            "portalProposalSent", "Değişiklik öneriniz iletildi.",
            "portalProposalApproved", "Değişikliğiniz uygulandı.",
            "portalProposalRejected", "Öneriniz kabul edilmedi.",
            "portalWhatNext", "Ne yapmak istersiniz?",
            "portalRejectQuote", "Teklifi reddet",
            "portalPdf", "PDF olarak teklif",
            "portalLoading", "Yükleniyor…",
            "portalSignTitle", "Kabul et ve imzala",
            "portalSignText", "Adınızı girerek bu teklifi kabul etmiş olursunuz. Bu, dijital"
                    + " imzanız yerine geçer.",
            "portalSignButton", "İmzala",
            "portalNoteOptional", "Not (isteğe bağlı)",
            "portalProposeText", "Değiştirmek istediğiniz adetleri düzenleyin. Önerinizi inceleyip"
                    + " güncellenmiş bir teklif göndeririz. Bir satırı kaldırmak için adedi"
                    + " 0 yapın.",
            "portalOnYourQuote", "Teklifinizde",
            "portalAddSection", "Ürün ekle",
            "portalOutOfStockWarning", "Stokta bulunmayan bir ürün seçtiniz. Önce bunu kabul etmemiz"
                    + " gerekiyor ve teslim süresini size bildireceğiz; ardından imzalamanız"
                    + " için güncellenmiş bir teklif alacaksınız.",
            "portalReasonOptional", "Gerekçe (isteğe bağlı)",
            "portalOptional", "isteğe bağlı",
            "portalFooter", "Sorularınız mı var? Bu teklifin geldiği e-postayı yanıtlamanız"
                    + " yeterli.");

}
