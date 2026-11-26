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

    /**
     * Goods cleared in the Netherlands through our limited fiscal representative
     * and supplied there to a Dutch VAT-registered buyer: the buyer accounts for
     * the VAT himself (art. 12.3 Wet op de omzetbelasting 1968).
     */
    VERLEGD_FISCAAL_VERTEGENWOORDIGER(
            "Btw verlegd via fiscaal vertegenwoordiger (NL)",
            "REVERSE CHARGE - btw verlegd naar de Nederlandse afnemer volgens "
                    + "artikel 12, lid 3 Wet op de omzetbelasting 1968."),
    /** Delivery outside the EU. */
    UITVOER(
            "Uitvoer buiten de EU",
            "Vrijstelling van BTW - uitvoer. "
                    + "Art. 39 W.BTW / art. 146 Richtlijn 2006/112/EG."),

    /**
     * A service that has nothing to do with goods (design, hosting, training), chosen on the document
     * (SalesVatChoice.SERVICE). Taxed in Belgium: a Belgian customer, or an EU customer without a VAT
     * number (the general rule for a private customer is the supplier's country).
     */
    DIENST_BINNENLAND(
            "Dienst · Belgische btw",
            null),

    /**
     * A service for a VAT-registered customer in another EU member state: taxed where the customer is
     * established, who accounts for the VAT himself (art. 21 § 2 W.BTW, art. 44 and 196 of the Directive).
     */
    DIENST_VERLEGD(
            "Dienst · btw verlegd",
            "Btw verlegd - dienst voor een belastingplichtige afnemer in een andere EU-lidstaat. "
                    + "Art. 21, § 2 W.BTW / art. 44 en 196 Richtlijn 2006/112/EG. "
                    + "Btw te voldoen door de afnemer."),

    /** A service for a customer established outside the EU: outside the scope of Belgian VAT. */
    DIENST_BUITEN_EU(
            "Dienst buiten de EU",
            "Niet onderworpen aan Belgische btw - dienst voor een afnemer buiten de EU. "
                    + "Art. 21, § 2 W.BTW / art. 44 Richtlijn 2006/112/EG.");

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
                case EL -> "Εγχώρια παράδοση";
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
                case EL -> "Ενδοκοινοτική παράδοση";
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
                case EL -> "Παράδοση εντός ΕΕ χωρίς αριθμό ΦΠΑ";
            };
            case VERLEGD_FISCAAL_VERTEGENWOORDIGER -> switch (language) {
                case NL -> "Btw verlegd via fiscaal vertegenwoordiger (NL)";
                case FR -> "TVA autoliquidée via représentant fiscal (NL)";
                case EN -> "Reverse charge via fiscal representative (NL)";
                case DE -> "Steuerschuldnerschaft über Fiskalvertreter (NL)";
                case ES -> "Inversión del sujeto pasivo vía representante fiscal (NL)";
                case PL -> "Odwrotne obciążenie przez przedstawiciela podatkowego (NL)";
                case PT -> "Autoliquidação via representante fiscal (NL)";
                case TR -> "Mali temsilci aracılığıyla ters vergilendirme (NL)";
                case EL -> "Αντίστροφη χρέωση μέσω φορολογικού αντιπροσώπου (NL)";
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
                case EL -> "Εξαγωγή εκτός ΕΕ";
            };
            case DIENST_BINNENLAND -> switch (language) {
                case NL -> "Dienst · Belgische btw";
                case FR -> "Prestation de services · TVA belge";
                case EN -> "Service · Belgian VAT";
                case DE -> "Dienstleistung · belgische MwSt.";
                case ES -> "Servicio · IVA belga";
                case PL -> "Usługa · belgijski VAT";
                case PT -> "Serviço · IVA belga";
                case TR -> "Hizmet · Belçika KDV'si";
                case EL -> "Υπηρεσία · βελγικός ΦΠΑ";
            };
            case DIENST_VERLEGD -> switch (language) {
                case NL -> "Dienst · btw verlegd";
                case FR -> "Prestation de services · autoliquidation";
                case EN -> "Service · reverse charge";
                case DE -> "Dienstleistung · Steuerschuldnerschaft des Leistungsempfängers";
                case ES -> "Servicio · inversión del sujeto pasivo";
                case PL -> "Usługa · odwrotne obciążenie";
                case PT -> "Serviço · autoliquidação";
                case TR -> "Hizmet · ters vergilendirme";
                case EL -> "Υπηρεσία · αντίστροφη χρέωση";
            };
            case DIENST_BUITEN_EU -> switch (language) {
                case NL -> "Dienst buiten de EU";
                case FR -> "Prestation de services hors UE";
                case EN -> "Service outside the EU";
                case DE -> "Dienstleistung außerhalb der EU";
                case ES -> "Servicio fuera de la UE";
                case PL -> "Usługa poza UE";
                case PT -> "Serviço fora da UE";
                case TR -> "AB dışı hizmet";
                case EL -> "Υπηρεσία εκτός ΕΕ";
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
                case EL -> "Απαλλαγή από ΦΠΑ - ενδοκοινοτική παράδοση. "
                        + "Άρθρο 39bis του βελγικού Κώδικα ΦΠΑ / άρθρο 138 της Οδηγίας 2006/112/ΕΚ. "
                        + "Ο ΦΠΑ αποδίδεται από τον πελάτη (αντίστροφη χρέωση).";
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
                case EL -> "Απαλλαγή από ΦΠΑ - εξαγωγή. "
                        + "Άρθρο 39 του βελγικού Κώδικα ΦΠΑ / άρθρο 146 της Οδηγίας 2006/112/ΕΚ.";
            };
            case VERLEGD_FISCAAL_VERTEGENWOORDIGER -> switch (language) {
                case NL -> "BTW VERLEGD - btw verlegd naar de Nederlandse afnemer volgens "
                        + "artikel 12, lid 3 Wet op de omzetbelasting 1968.";
                case FR -> "AUTOLIQUIDATION - TVA transférée au client néerlandais conformément à "
                        + "l'article 12, paragraphe 3, de la loi néerlandaise sur la TVA (Wet OB 1968).";
                case EN -> "REVERSE CHARGE - VAT shifted to the Dutch customer according to "
                        + "article 12.3 Dutch VAT Law (Wet OB 1968).";
                case DE -> "STEUERSCHULDNERSCHAFT DES LEISTUNGSEMPFÄNGERS - Umsatzsteuer auf den niederländischen "
                        + "Abnehmer verlagert gemäß Artikel 12 Absatz 3 des niederländischen Umsatzsteuergesetzes (Wet OB 1968).";
                case ES -> "INVERSIÓN DEL SUJETO PASIVO - IVA trasladado al cliente neerlandés conforme al "
                        + "artículo 12, apartado 3, de la ley neerlandesa del IVA (Wet OB 1968).";
                case PL -> "ODWROTNE OBCIĄŻENIE - VAT przeniesiony na holenderskiego nabywcę zgodnie z "
                        + "art. 12 ust. 3 holenderskiej ustawy o VAT (Wet OB 1968).";
                case PT -> "AUTOLIQUIDAÇÃO - IVA transferido para o cliente neerlandês nos termos do "
                        + "artigo 12.º, n.º 3, da lei neerlandesa do IVA (Wet OB 1968).";
                case TR -> "TERS VERGİLENDİRME - KDV, Hollanda KDV Kanunu (Wet OB 1968) madde 12/3 uyarınca "
                        + "Hollandalı alıcıya devredilmiştir.";
                case EL -> "ΑΝΤΙΣΤΡΟΦΗ ΧΡΕΩΣΗ - ο ΦΠΑ μεταφέρεται στον Ολλανδό πελάτη σύμφωνα με "
                        + "το άρθρο 12, παράγραφος 3, του ολλανδικού νόμου περί ΦΠΑ (Wet OB 1968).";
            };
            case DIENST_VERLEGD -> switch (language) {
                case NL -> "Btw verlegd - dienst voor een belastingplichtige afnemer in een andere EU-lidstaat. "
                        + "Art. 21, § 2 W.BTW / art. 44 en 196 Richtlijn 2006/112/EG. "
                        + "Btw te voldoen door de afnemer.";
                case FR -> "Autoliquidation - prestation de services à un assujetti établi dans un autre État membre. "
                        + "Art. 21, § 2 C.TVA / art. 44 et 196 Directive 2006/112/CE. "
                        + "TVA due par le preneur.";
                case EN -> "Reverse charge - supply of services to a taxable person in another EU member state. "
                        + "Art. 21 § 2 Belgian VAT Code / art. 44 and 196 Directive 2006/112/EC. "
                        + "VAT to be accounted for by the customer.";
                case DE -> "Steuerschuldnerschaft des Leistungsempfängers - sonstige Leistung an einen Steuerpflichtigen "
                        + "in einem anderen EU-Mitgliedstaat. "
                        + "Art. 21 § 2 belg. MwStG / Art. 44 und 196 Richtlinie 2006/112/EG.";
                case ES -> "Inversión del sujeto pasivo - prestación de servicios a un sujeto pasivo de otro Estado miembro. "
                        + "Art. 21 § 2 Código del IVA belga / art. 44 y 196 Directiva 2006/112/CE. "
                        + "IVA a liquidar por el destinatario.";
                case PL -> "Odwrotne obciążenie - świadczenie usług na rzecz podatnika z innego państwa członkowskiego UE. "
                        + "Art. 21 § 2 belgijskiej ustawy o VAT / art. 44 i 196 dyrektywy 2006/112/WE. "
                        + "VAT rozlicza nabywca.";
                case PT -> "Autoliquidação - prestação de serviços a um sujeito passivo de outro Estado-Membro. "
                        + "Art. 21 § 2 do Código do IVA belga / art. 44 e 196 da Diretiva 2006/112/CE. "
                        + "IVA devido pelo adquirente.";
                case TR -> "Ters vergilendirme - başka bir AB üye devletindeki vergi mükellefine hizmet sunumu. "
                        + "Belçika KDV Kanunu md. 21 § 2 / 2006/112/AT sayılı Direktif md. 44 ve 196. "
                        + "KDV alıcı tarafından beyan edilir.";
                case EL -> "Αντίστροφη χρέωση - παροχή υπηρεσιών σε υποκείμενο στον φόρο σε άλλο κράτος μέλος της ΕΕ. "
                        + "Άρθρο 21 § 2 του βελγικού Κώδικα ΦΠΑ / άρθρα 44 και 196 της Οδηγίας 2006/112/ΕΚ. "
                        + "Ο ΦΠΑ αποδίδεται από τον λήπτη.";
            };
            case DIENST_BUITEN_EU -> switch (language) {
                case NL -> "Niet onderworpen aan Belgische btw - dienst voor een afnemer buiten de EU. "
                        + "Art. 21, § 2 W.BTW / art. 44 Richtlijn 2006/112/EG.";
                case FR -> "Non soumis à la TVA belge - prestation de services à un preneur établi hors de l'UE. "
                        + "Art. 21, § 2 C.TVA / art. 44 Directive 2006/112/CE.";
                case EN -> "Not subject to Belgian VAT - supply of services to a customer established outside the EU. "
                        + "Art. 21 § 2 Belgian VAT Code / art. 44 Directive 2006/112/EC.";
                case DE -> "In Belgien nicht steuerbar - sonstige Leistung an einen Leistungsempfänger außerhalb der EU. "
                        + "Art. 21 § 2 belg. MwStG / Art. 44 Richtlinie 2006/112/EG.";
                case ES -> "No sujeto al IVA belga - prestación de servicios a un destinatario establecido fuera de la UE. "
                        + "Art. 21 § 2 Código del IVA belga / art. 44 Directiva 2006/112/CE.";
                case PL -> "Nie podlega belgijskiemu VAT - świadczenie usług na rzecz nabywcy spoza UE. "
                        + "Art. 21 § 2 belgijskiej ustawy o VAT / art. 44 dyrektywy 2006/112/WE.";
                case PT -> "Não sujeito a IVA belga - prestação de serviços a um adquirente estabelecido fora da UE. "
                        + "Art. 21 § 2 do Código do IVA belga / art. 44 da Diretiva 2006/112/CE.";
                case TR -> "Belçika KDV'sine tabi değildir - AB dışında yerleşik alıcıya hizmet sunumu. "
                        + "Belçika KDV Kanunu md. 21 § 2 / 2006/112/AT sayılı Direktif md. 44.";
                case EL -> "Δεν υπόκειται σε βελγικό ΦΠΑ - παροχή υπηρεσιών σε λήπτη εγκατεστημένο εκτός ΕΕ. "
                        + "Άρθρο 21 § 2 του βελγικού Κώδικα ΦΠΑ / άρθρο 44 της Οδηγίας 2006/112/ΕΚ.";
            };
            case BINNENLAND, EU_ZONDER_BTW_NUMMER, DIENST_BINNENLAND -> null;
        };
    }

    public boolean isExempt() {
        return this == INTRACOMMUNAUTAIR || this == UITVOER || this == VERLEGD_FISCAAL_VERTEGENWOORDIGER
                || this == DIENST_VERLEGD || this == DIENST_BUITEN_EU;
    }

    /** A service that has nothing to do with goods: no delivery, no incoterm, no country minimum. */
    public boolean isService() {
        return this == DIENST_BINNENLAND || this == DIENST_VERLEGD || this == DIENST_BUITEN_EU;
    }
}
