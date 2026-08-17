package be.enrosed.sales.domain;

/**
 * Hoe de BTW op een levering behandeld wordt.
 *
 * Wij zitten in België, dus:
 *  - binnenland          -> Belgische BTW
 *  - andere EU-lidstaat  -> vrijgesteld als de klant een geldig BTW-nummer
 *                           heeft; de klant verlegt de heffing zelf
 *  - buiten de EU        -> uitvoer, vrijgesteld
 *
 * LET OP: de wetsartikelen hieronder zijn de gangbare verwijzingen, maar
 * BTW-regels hangen af van de concrete levering en veranderen. Laat de
 * teksten een keer nakijken door je boekhouder voor je ze op echte facturen
 * zet. De app zorgt dat de vermelding er staat; of ze juist is voor jouw
 * situatie, is een vraag voor een accountant.
 */
public enum VatTreatment {

    /** Levering binnen België: gewoon Belgisch tarief. */
    BINNENLAND(
            "Binnenlandse levering",
            null),

    /**
     * Intracommunautaire levering naar een BTW-plichtige in een andere
     * EU-lidstaat. Nultarief; de afnemer verlegt de heffing.
     */
    INTRACOMMUNAUTAIR(
            "Intracommunautaire levering",
            "Vrijstelling van BTW - intracommunautaire levering. "
                    + "Art. 39bis W.BTW / art. 138 Richtlijn 2006/112/EG. "
                    + "BTW te voldoen door de afnemer."),

    /**
     * EU-klant zonder geldig BTW-nummer. Dan is het geen intracommunautaire
     * levering en blijft er BTW verschuldigd - welke, hangt af van de
     * afstandsverkoopregels. Bewust niet automatisch op nul gezet.
     */
    EU_ZONDER_BTW_NUMMER(
            "EU-levering zonder BTW-nummer",
            null),

    /** Levering buiten de EU. */
    UITVOER(
            "Uitvoer buiten de EU",
            "Vrijstelling van BTW - uitvoer. "
                    + "Art. 39 W.BTW / art. 146 Richtlijn 2006/112/EG.");

    private final String label;
    private final String legalMention;

    VatTreatment(String label, String legalMention) {
        this.label = label;
        this.legalMention = legalMention;
    }

    public String label() { return label; }

    /** Zin die op de offerte en de factuur hoort te staan; null bij gewone BTW. */
    public String legalMention() { return legalMention; }

    /**
     * Hetzelfde in de taal van de klant.
     *
     * De wetsartikelen blijven staan zoals ze heten - Art. 39bis W.BTW is geen
     * vertaalbare tekst maar een verwijzing - maar de zin eromheen hoort in de
     * taal van de klant. Een Franse klant die "Vrijstelling van BTW" leest weet
     * niet of hij BTW moet betalen, en dat is precies wat de vermelding moet
     * duidelijk maken.
     */
    public String labelIn(be.enrosed.shared.Language language) {
        return switch (this) {
            case BINNENLAND -> switch (language) {
                case NL -> "Binnenlandse levering";
                case FR -> "Livraison nationale";
                case EN -> "Domestic supply";
                case DE -> "Inlandslieferung";
                case ES -> "Entrega nacional";
                case PL -> "Dostawa krajowa";
                case PT -> "Entrega nacional";
                case TR -> "Yurt içi teslimat";
            };
            case INTRACOMMUNAUTAIR -> switch (language) {
                case NL -> "Intracommunautaire levering";
                case FR -> "Livraison intracommunautaire";
                case EN -> "Intra-Community supply";
                case DE -> "Innergemeinschaftliche Lieferung";
                case ES -> "Entrega intracomunitaria";
                case PL -> "Wewnątrzwspólnotowa dostawa towarów";
                case PT -> "Entrega intracomunitária";
                case TR -> "Topluluk içi teslimat";
            };
            case EU_ZONDER_BTW_NUMMER -> switch (language) {
                case NL -> "EU-levering zonder BTW-nummer";
                case FR -> "Livraison UE sans numéro de TVA";
                case EN -> "EU supply without VAT number";
                case DE -> "EU-Lieferung ohne USt-IdNr.";
                case ES -> "Entrega UE sin NIF-IVA";
                case PL -> "Dostawa UE bez numeru VAT";
                case PT -> "Entrega UE sem NIF";
                case TR -> "Vergi numarasız AB teslimatı";
            };
            case UITVOER -> switch (language) {
                case NL -> "Uitvoer buiten de EU";
                case FR -> "Exportation hors UE";
                case EN -> "Export outside the EU";
                case DE -> "Ausfuhr außerhalb der EU";
                case ES -> "Exportación fuera de la UE";
                case PL -> "Eksport poza UE";
                case PT -> "Exportação fora da UE";
                case TR -> "AB dışına ihracat";
            };
        };
    }

    /** De wettelijke vermelding in de taal van de klant; null bij gewone BTW. */
    public String legalMentionIn(be.enrosed.shared.Language language) {
        return switch (this) {
            case INTRACOMMUNAUTAIR -> switch (language) {
                case NL -> "Vrijstelling van BTW - intracommunautaire levering. "
                        + "Art. 39bis W.BTW / art. 138 Richtlijn 2006/112/EG. "
                        + "BTW te voldoen door de afnemer.";
                case FR -> "Exonération de TVA - livraison intracommunautaire. "
                        + "Art. 39bis C.TVA / art. 138 Directive 2006/112/CE. "
                        + "Autoliquidation par l'acquéreur.";
                case EN -> "VAT exempt - intra-Community supply. "
                        + "Art. 39bis Belgian VAT Code / art. 138 Directive 2006/112/EC. "
                        + "VAT to be accounted for by the customer (reverse charge).";
                case DE -> "Steuerfreie innergemeinschaftliche Lieferung. "
                        + "Art. 39bis belg. MwStG / Art. 138 Richtlinie 2006/112/EG. "
                        + "Steuerschuldnerschaft des Leistungsempfängers.";
                case ES -> "Exención de IVA - entrega intracomunitaria. "
                        + "Art. 39bis Código del IVA belga / art. 138 Directiva 2006/112/CE. "
                        + "IVA a liquidar por el adquirente (inversión del sujeto pasivo).";
                case PL -> "Zwolnienie z VAT - wewnątrzwspólnotowa dostawa towarów. "
                        + "Art. 39bis belgijskiej ustawy o VAT / art. 138 dyrektywy 2006/112/WE. "
                        + "VAT rozlicza nabywca (odwrotne obciążenie).";
                case PT -> "Isenção de IVA - entrega intracomunitária. "
                        + "Art. 39bis do Código do IVA belga / art. 138 da Diretiva 2006/112/CE. "
                        + "IVA devido pelo adquirente (autoliquidação).";
                case TR -> "KDV istisnası - Topluluk içi teslimat. "
                        + "Belçika KDV Kanunu md. 39bis / 2006/112/AT sayılı Direktif md. 138. "
                        + "KDV alıcı tarafından beyan edilir.";
            };
            case UITVOER -> switch (language) {
                case NL -> "Vrijstelling van BTW - uitvoer. "
                        + "Art. 39 W.BTW / art. 146 Richtlijn 2006/112/EG.";
                case FR -> "Exonération de TVA - exportation. "
                        + "Art. 39 C.TVA / art. 146 Directive 2006/112/CE.";
                case EN -> "VAT exempt - export. "
                        + "Art. 39 Belgian VAT Code / art. 146 Directive 2006/112/EC.";
                case DE -> "Steuerfreie Ausfuhrlieferung. "
                        + "Art. 39 belg. MwStG / Art. 146 Richtlinie 2006/112/EG.";
                case ES -> "Exención de IVA - exportación. "
                        + "Art. 39 Código del IVA belga / art. 146 Directiva 2006/112/CE.";
                case PL -> "Zwolnienie z VAT - eksport. "
                        + "Art. 39 belgijskiej ustawy o VAT / art. 146 dyrektywy 2006/112/WE.";
                case PT -> "Isenção de IVA - exportação. "
                        + "Art. 39 do Código do IVA belga / art. 146 da Diretiva 2006/112/CE.";
                case TR -> "KDV istisnası - ihracat. "
                        + "Belçika KDV Kanunu md. 39 / 2006/112/AT sayılı Direktif md. 146.";
            };
            case BINNENLAND, EU_ZONDER_BTW_NUMMER -> null;
        };
    }

    public boolean isExempt() {
        return this == INTRACOMMUNAUTAIR || this == UITVOER;
    }
}
