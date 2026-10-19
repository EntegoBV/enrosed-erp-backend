package be.enrosed.tica;

import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class TicaSettlementXmlParserTest {
    private final TicaSettlementXmlParser parser = new TicaSettlementXmlParser();
    private static String fixture() {
        return """
            <CreditNote xmlns="urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"
              xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
              xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
              <cbc:ID>123456789</cbc:ID><cbc:IssueDate>2026-10-19</cbc:IssueDate><cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
              <cac:AccountingSupplierParty><cac:Party><cac:PartyTaxScheme><cbc:CompanyID>BE0428038927</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme></cac:Party></cac:AccountingSupplierParty>
              <cac:AccountingCustomerParty><cac:Party><cac:PartyTaxScheme><cbc:CompanyID>BE1034273386</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme></cac:Party></cac:AccountingCustomerParty>
              <cac:PaymentMeans><cbc:PaymentDueDate>2026-10-19</cbc:PaymentDueDate></cac:PaymentMeans>
              <cac:TaxTotal><cbc:TaxAmount currencyID="EUR">37.80</cbc:TaxAmount></cac:TaxTotal>
              <cac:LegalMonetaryTotal><cbc:LineExtensionAmount currencyID="EUR">180.00</cbc:LineExtensionAmount>
                <cbc:TaxExclusiveAmount currencyID="EUR">180.00</cbc:TaxExclusiveAmount><cbc:TaxInclusiveAmount currencyID="EUR">217.80</cbc:TaxInclusiveAmount>
                <cbc:PayableAmount currencyID="EUR">217.80</cbc:PayableAmount></cac:LegalMonetaryTotal>
            """ + line("Omzet week 42", "1", "200.00") + line("Commissie week 42 - 10.00%", "-1", "-20.00") + "</CreditNote>";
    }
    private static String line(String name, String quantity, String amount) {
        return "<cac:CreditNoteLine><cbc:CreditedQuantity>" + quantity + "</cbc:CreditedQuantity><cbc:LineExtensionAmount currencyID=\"EUR\">" + amount
                + "</cbc:LineExtensionAmount><cac:Item><cbc:Name>" + name + "</cbc:Name><cac:ClassifiedTaxCategory><cbc:ID>S</cbc:ID><cbc:Percent>21</cbc:Percent>"
                + "<cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:ClassifiedTaxCategory></cac:Item></cac:CreditNoteLine>";
    }
    private TicaSettlementPdfParser.Parsed parse(String text) { return parser.parse(text.getBytes(StandardCharsets.UTF_8)); }
    @Test void readsCreditnoteWithoutRequiringPdf() {
        var parsed = parse(fixture());
        assertEquals(new BigDecimal("200.00"), parsed.salesExcl());
        assertEquals(new BigDecimal("20.00"), parsed.commissionExcl());
        assertEquals(new BigDecimal("217.80"), parsed.netReceivable());
        assertEquals(LocalDate.of(2026,10,12), parsed.periodFrom());
        assertEquals(LocalDate.of(2026,10,19), parsed.dueDate());
    }
    @Test void rejectsWrongPartiesCurrencyAmountsAndWeeks() {
        for (String bad : new String[] {fixture().replace("BE1034273386", "BE0000000000"), fixture().replace("BE0428038927", "BE0000000000"),
                fixture().replace("currencyID=\"EUR\"", "currencyID=\"USD\""), fixture().replace("37.80", "37.81"),
                fixture().replace("217.80</cbc:PayableAmount>", "218.80</cbc:PayableAmount>"), fixture().replace("Commissie week 42", "Commissie week 41")})
            assertThrows(BusinessRuleException.class, () -> parse(bad));
    }
    @Test void refusesAdditionalRowsAndAmbiguousDocumentFields() {
        assertThrows(BusinessRuleException.class, () -> parse(fixture().replace("</CreditNote>", line("Andere kost", "1", "0.00") + "</CreditNote>")));
        assertThrows(BusinessRuleException.class, () -> parse(fixture().replace("<cbc:ID>123456789</cbc:ID>", "<cbc:ID>123456789</cbc:ID><cbc:ID>987654321</cbc:ID>")));
        assertThrows(BusinessRuleException.class, () -> parse(fixture().replace("<cac:PaymentMeans>", "<cac:AllowanceCharge/><cac:PaymentMeans>")));
    }
    @Test void forbidsDoctypeExternalEntitiesAndMalformedXml() {
        assertThrows(BusinessRuleException.class, () -> parse("<!DOCTYPE CreditNote [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>" + fixture()));
        assertThrows(BusinessRuleException.class, () -> parse("<CreditNote>"));
    }
    @Test void rejectsExponentAndOversizedDecimalsBeforeArithmetic() {
        for (String invalid : new String[] {"1E+999999999", "0.00000000000000000000000001", "9".repeat(1000)}) {
            assertThrows(BusinessRuleException.class, () -> parse(fixture().replace("200.00", invalid)));
            assertThrows(BusinessRuleException.class, () -> parse(fixture().replace("<cbc:Percent>21", "<cbc:Percent>" + invalid)));
            assertThrows(BusinessRuleException.class, () -> parse(fixture().replace("<cbc:CreditedQuantity>1", "<cbc:CreditedQuantity>" + invalid)));
        }
    }
}
