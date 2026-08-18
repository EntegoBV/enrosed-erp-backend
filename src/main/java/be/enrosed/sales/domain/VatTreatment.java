package be.enrosed.sales.domain;

/**
 * How VAT on a delivery is treated.
 *
 * We are based in Belgium, so:
 *  - domestic            -> Belgian VAT
 *  - other EU state      -> exempt when the customer has a valid VAT number;
 *                           the customer reverse-charges the tax
 *  - outside the EU      -> export, exempt
 *
 * CAUTION: the legal articles below are the customary references, but VAT
 * rules depend on the concrete delivery and they change. Have the texts
 * checked once by your accountant before they go on real invoices. The app
 * makes sure the mention is there; whether it is right for your situation
 * is a question for an accountant.
 */
public enum VatTreatment {

    /** Delivery within Belgium: plain Belgian rate. */
    BINNENLAND(
            "Binnenlandse levering",
            null),

    /**
     * Intra-community supply to a VAT-registered customer in another EU
     * member state. Zero rate; the buyer reverse-charges the tax.
     */
    INTRACOMMUNAUTAIR(
            "Intracommunautaire levering",
            "Vrijstelling van BTW - intracommunautaire levering. "
                    + "Art. 39bis W.BTW / art. 138 Richtlijn 2006/112/EG. "
                    + "BTW te voldoen door de afnemer."),

    /**
     * EU customer without a valid VAT number. Then it is not an
     * intra-community supply and VAT remains due - which VAT depends on the
     * distance-selling rules. Deliberately not zeroed automatically.
     */
    EU_ZONDER_BTW_NUMMER(
            "EU-levering zonder BTW-nummer",
            null),

    /** Delivery outside the EU. */
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

    /** Sentence that belongs on the quote and invoice; null for regular VAT. */
    public String legalMention() { return legalMention; }

    /**
     * The same in the customer's language.
     *
     * The legal articles stay as they are named - Art. 39bis W.BTW is a
     * reference, not translatable text - but the sentence around them belongs
     * in the customer's language. A French customer reading "Vrijstelling van
     * BTW" does not know whether they owe VAT, and that is exactly what the
     * mention must make clear.
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

    /** The legal mention in the customer's language; null for regular VAT. */
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
