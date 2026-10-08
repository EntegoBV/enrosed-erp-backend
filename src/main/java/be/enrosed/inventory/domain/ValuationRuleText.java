package be.enrosed.inventory.domain;

/**
 * The valuation rule as it goes into the inventory book: FIFO per receipt
 * lot. The text is stored on the rule row and copied onto every closing, so
 * a final closing keeps the wording it was made under.
 */
public final class ValuationRuleText {

    public static final String METHOD = "FIFO_LOT";
    public static final String METHOD_LABEL = "FIFO per ontvangen partij";
    public static final String RULE_VERSION = "1";

    /** The placeholder {jaar} is the first financial year the rule applies to. */
    public static final String FIFO_LOT_V1 = ""
            + "Handelsgoederen worden gewaardeerd tegen aanschaffingswaarde (in het ERP: aanschafwaarde), of tegen de lagere marktwaarde op de afsluitdatum. "
            + "De aanschafwaarde wordt bepaald per ontvangen partij (één product op één container) en omvat: de prijs van de leverancier na aftrek van prijsverminderingen en prijscreditnota's; het transport tot de EU-grens wanneer de leverancier dat aanrekent; de invoerrechten, het transport en de kosten van douane en aankomst tot in het eerste magazijn; de inspectie en de andere kosten van die zending. "
            + "De bijkomende kosten van de aankoop (vervoer, invoerrechten, douane- en aankomstkosten, inspectie) maken dus deel uit van de aanschafwaarde. "
            + "Maken er geen deel van uit: de Enrosed kost en andere eigen kosten van de onderneming, marges, terugvorderbare btw, bank- en betalingskosten en andere kosten die niet bij de zending horen (in het ERP geboekt onder \"Bijkomende kosten\"), opslagkosten na aankomst en koersverschillen. "
            + "Kosten zijn opgenomen voor het bedrag dat betaald of verschuldigd is; een bedrag dat nog niet vaststaat is opgenomen aan het best gekende bedrag (de Afspraak op de container of het door de gebruiker ingevoerde bedrag) en als geschat vermeld. "
            + "Aankopen in vreemde munt behouden hun eurowaarde: betalingen tot en met de dag waarop eigendom of risico van de goederen overging (de datum die de gebruiker daarvoor opgaf, anders de ontvangstdag; eens opgenomen in een definitieve afsluiting blijft die datum voor die container dezelfde) aan de geboekte eurowaarde, latere betalingen en openstaande bedragen aan de koers die op de container is ingevoerd; er is geen herwaardering aan slotkoers. "
            + "De prijs van de leverancier wordt over de producten verdeeld volgens de eigen inkoopprijs van elk product en gedeeld door het aantal dat de leverancier aanrekende. "
            + "De kosten van de zending worden over de producten verdeeld met de sleutels van de container (varianten van één reeks krijgen daarbij hetzelfde aandeel per stuk wanneer de container dat zo verdeelt) en gedeeld door het ontvangen aantal. "
            + "Ontbrekende en beschadigde stuks worden niet gewaardeerd en hun kost wordt niet op de goede stuks gelegd. "
            + "Een tegoed van de leverancier voor prijs of kwaliteit verlaagt de aanschafwaarde; een tegoed voor tekort of schade verlaagt de waarde per stuk niet; een tegoed dat al van de betaling is afgetrokken wordt niet nogmaals afgetrokken. "
            + "Gelijksoortige goederen worden gewaardeerd volgens de FIFO-methode per ontvangen partij: stuks die op de container zelf als beschadigd zijn geregistreerd, tellen niet mee in die partij; elke andere uitgaande beweging wordt geacht uit de oudste partij te komen, zodat de eindvoorraad bestaat uit de laatst ontvangen partijen. "
            + "Voorraad uit een vorig afgesloten boekjaar behoudt de waarde per stuk van die afsluiting. "
            + "Voorraad zonder gekende partij staat aan een gedocumenteerde beginwaarde. "
            + "Een lagere marktwaarde wordt toegepast op de partijen met de hoogste waarde per stuk eerst. "
            + "Partnercontainers, goederen onderweg, gefactureerde maar nog niet afgepunte goederen en goederen van derden worden afzonderlijk vermeld. "
            + "Deze regel geldt sinds boekjaar {jaar}.";

    private ValuationRuleText() {}

    public static String render(int effectiveFromYear) {
        return FIFO_LOT_V1.replace("{jaar}", String.valueOf(effectiveFromYear));
    }
}
