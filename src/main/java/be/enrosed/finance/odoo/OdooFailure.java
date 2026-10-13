package be.enrosed.finance.odoo;

/** Only a controlled code crosses the provider boundary; never keep upstream bodies or credentials. */
public final class OdooFailure extends RuntimeException {
    public final String code;
    public OdooFailure(String code) { super(code); this.code = code; }
    public String userMessage() {
        return switch (code) {
            case "DISABLED" -> "De Odoo-koppeling is uitgeschakeld.";
            case "NOT_CONFIGURED" -> "Vul de Odoo-configuratie op de server aan.";
            case "INVALID_CONFIGURATION" -> "De Odoo-configuratie op de server is ongeldig.";
            case "AUTH_FAILED" -> "Odoo weigert de API-sleutel.";
            case "ACCESS_DENIED" -> "De Odoo-gebruiker heeft onvoldoende rechten.";
            case "JSON2_UNAVAILABLE" -> "De Odoo JSON-2 API is niet beschikbaar. Controleer versie 19 en API-toegang.";
            case "UNSUPPORTED_SCHEMA" -> "Deze Odoo-installatie mist vereiste velden of modules.";
            case "COMPANY_UNAVAILABLE" -> "De ingestelde Odoo-administratie is niet toegankelijk.";
            case "NO_JOURNALS" -> "Er zijn geen toegankelijke bankdagboeken binnen de ingestelde selectie.";
            case "JOURNAL_ACCOUNT_MISMATCH" -> "Het Odoo-bankdagboek hoort niet bij de gekoppelde rekening. Controleer de rekeningverbinding; er zijn geen nieuwe bankregels geïmporteerd.";
            case "PURCHASE_DOCUMENT_UNAVAILABLE" -> "Kies een ontvangen factuur of leverancierscreditnota die niet geannuleerd is.";
            case "PURCHASE_CURRENCY_UNSUPPORTED" -> "Deze ontvangen factuur is niet in euro; registreer eerst de juiste omrekening.";
            case "PURCHASE_PREVIEW_CHANGED" -> "De factuur is gewijzigd sinds het voorbeeld. Open het voorbeeld opnieuw; er is niets geregistreerd.";
            case "BUSY" -> "Er loopt al een Odoo-banksynchronisatie.";
            case "LEASE_LOST" -> "De banksynchronisatie is onderbroken. Start opnieuw; bestaande imports blijven behouden.";
            case "RATE_LIMITED" -> "Odoo laat tijdelijk geen extra API-aanvragen toe. Probeer later opnieuw.";
            case "NOT_FOUND" -> "Dit Odoo-document of deze bijlage is niet beschikbaar.";
            case "ATTACHMENT_TOO_LARGE" -> "Deze Odoo-bijlage is te groot om hier te downloaden (maximaal 15 MB).";
            case "INVALID_REQUEST" -> "Ongeldige aanvraag voor de Odoo-koppeling.";
            case "INVALID_RESPONSE" -> "Odoo gaf onvolledige of ongeldige gegevens terug.";
            case "OUTBOUND_DISABLED" -> "Verzenden via Odoo is nog uitgeschakeld. Het factuurvoorbeeld blijft beschikbaar.";
            case "OUTBOUND_NOT_CONFIGURED" -> "De Odoo-verbinding en factuurinstellingen moeten nog worden aangevuld.";
            case "OUTBOX_PREVIEW_REQUIRED" -> "Kies de klant en bewaar eerst het gecontroleerde factuurvoorbeeld.";
            case "OUTBOX_NOT_ISSUED" -> "Alleen een uitgereikte factuur of creditnota kan naar Odoo worden overgezet.";
            case "OUTBOX_UNSUPPORTED_DOCUMENT" -> "Deze factuurvorm kan nog niet ongewijzigd naar Odoo worden overgezet. De factuur is niet verstuurd.";
            case "OUTBOX_CHANGED" -> "De factuur of koppeling wijkt af van het bewaarde voorbeeld. Er is niets opnieuw verstuurd.";
            case "OUTBOX_SOURCE_CHANGED" -> "Dit factuurvoorbeeld hoort bij een andere Odoo-administratie.";
            case "OUTBOX_PARTNER_MISMATCH" -> "Naam, btw-nummer of adres van de klant komen niet exact overeen met Odoo.";
            case "OUTBOX_COMPANY_MISMATCH" -> "Het btw-nummer van de ingestelde Odoo-administratie komt niet overeen met ENROSED.";
            case "OUTBOX_TAX_MISMATCH" -> "De ingestelde Odoo-btw-code past niet bij het factuurvoorbeeld.";
            case "OUTBOX_TEST_MODE_REQUIRED" -> "Deze testkoppeling vereist een Odoo Peppol-testregistratie. Live verzending is geblokkeerd.";
            case "CREDIT_REFERENCE_MISSING" -> "De oorspronkelijke factuur van deze creditnota is niet eenduidig als geboekte factuur in Odoo gevonden.";
            case "OUTBOX_REMOTE_MISMATCH" -> "De Odoo-factuur wijkt af van het bewaarde voorbeeld. Controleer de factuur in Odoo; verzending is geblokkeerd.";
            case "OUTBOX_REMOTE_EXISTS" -> "Er bestaat al een Odoo-factuur voor dit document. Haal de status op voordat je verdergaat.";
            case "OUTBOX_RETRY_BLOCKED" -> "Deze stap mag niet opnieuw worden uitgevoerd. Haal de Odoo-status op en controleer de bestaande factuur.";
            case "OUTBOX_UNKNOWN_CREATE" -> "De uitkomst van het aanmaken is nog onzeker. Haal de status op; er wordt geen tweede factuur aangemaakt.";
            case "OUTBOX_UNKNOWN_SEND" -> "De verzenduitkomst is nog onzeker. Haal de status op; er wordt niet automatisch opnieuw verstuurd.";
            default -> "Odoo is tijdelijk niet bereikbaar. Probeer opnieuw.";
        };
    }
}
