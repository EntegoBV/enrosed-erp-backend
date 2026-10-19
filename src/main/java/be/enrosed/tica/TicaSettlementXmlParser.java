package be.enrosed.tica;

import be.enrosed.shared.BusinessRuleException;
import org.w3c.dom.Element;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Strict UBL credit-note reader for a TICA Brussels weekly sales/commission settlement. */
public final class TicaSettlementXmlParser {
    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    private static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    public TicaSettlementPdfParser.Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > TicaSettlementPdfParser.MAX_BYTES) throw invalid();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!"CreditNote".equals(root.getLocalName()) || !"urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2".equals(root.getNamespaceURI())) throw invalid();
            if (!"EUR".equals(text(root, CBC, "DocumentCurrencyCode"))) throw invalid();
            if (!"BE0428038927".equals(partyVat(root, "AccountingSupplierParty"))
                    || !"BE1034273386".equals(partyVat(root, "AccountingCustomerParty"))) throw invalid();
            String reference = text(root, CBC, "ID");
            if (!reference.matches("[0-9]{5,30}")) throw invalid();
            LocalDate date = LocalDate.parse(text(root, CBC, "IssueDate"));
            // UBL CreditNote places the payment due date under PaymentMeans (BT-9).
            LocalDate due = LocalDate.parse(text(one(root, CAC, "PaymentMeans"), CBC, "PaymentDueDate"));
            if (!children(root, CAC, "AllowanceCharge").isEmpty()) throw invalid();
            List<Element> lines = children(root, CAC, "CreditNoteLine");
            if (lines.size() != 2) throw invalid();
            BigDecimal sales = null, fee = null; Integer week = null;
            for (Element line : lines) {
                var item = one(line, CAC, "Item");
                var name = text(item, CBC, "Name").replaceAll("\\s+", " ").strip();
                var matcher = Pattern.compile("^(Omzet|Commissie) week ([0-9]{1,2})(?:\\s*-\\s*[0-9]+[.,][0-9]+%)?$", Pattern.CASE_INSENSITIVE).matcher(name);
                if (!matcher.matches() || !children(line, CAC, "AllowanceCharge").isEmpty()) throw invalid();
                int lineWeek = Integer.parseInt(matcher.group(2));
                if (lineWeek < 1 || lineWeek > 53 || week != null && week != lineWeek) throw invalid();
                week = lineWeek;
                var tax = one(item, CAC, "ClassifiedTaxCategory");
                if (!"S".equals(text(tax, CBC, "ID")) || decimal(text(tax, CBC, "Percent")).compareTo(new BigDecimal("21")) != 0
                        || !"VAT".equals(text(one(tax, CAC, "TaxScheme"), CBC, "ID"))) throw invalid();
                BigDecimal amount = money(line, "LineExtensionAmount");
                BigDecimal quantity = decimal(text(line, CBC, "CreditedQuantity"));
                if (matcher.group(1).equalsIgnoreCase("Omzet")) {
                    if (sales != null || amount.signum() <= 0 || quantity.signum() <= 0) throw invalid();
                    sales = amount;
                } else {
                    if (fee != null || amount.signum() > 0 || quantity.signum() >= 0) throw invalid();
                    fee = amount.negate();
                }
            }
            if (sales == null || fee == null || fee.compareTo(sales) > 0) throw invalid();
            BigDecimal salesVat = sales.multiply(new BigDecimal("0.21")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal feeVat = fee.multiply(new BigDecimal("0.21")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = sales.subtract(fee), vat = salesVat.subtract(feeVat), payable = net.add(vat);
            var totals = one(root, CAC, "LegalMonetaryTotal");
            if (!net.equals(money(totals, "LineExtensionAmount")) || !net.equals(money(totals, "TaxExclusiveAmount"))
                    || !payable.equals(money(totals, "TaxInclusiveAmount")) || !payable.equals(money(totals, "PayableAmount"))
                    || !vat.equals(money(one(root, CAC, "TaxTotal"), "TaxAmount"))) throw invalid();
            for (String adjustment : List.of("PrepaidAmount", "PayableRoundingAmount", "AllowanceTotalAmount", "ChargeTotalAmount")) {
                if (!children(totals, CBC, adjustment).isEmpty() && money(totals, adjustment).signum() != 0) throw invalid();
            }
            LocalDate monday = null;
            for (int days = 0; days <= 90; days++) {
                LocalDate candidate = date.minusDays(days);
                if (candidate.getDayOfWeek().getValue() == 1 && candidate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == week
                        && !candidate.plusDays(6).isAfter(date)) { monday = candidate; break; }
            }
            if (monday == null) throw invalid();
            return new TicaSettlementPdfParser.Parsed(reference, "TICA - Brussel", monday, monday.plusDays(6), date, due,
                    sales, salesVat, fee, feeVat, payable, List.of("Bedragen gelezen uit het Peppol-brondocument. Controleer de afrekening tegenover de TICA-verkopen."));
        } catch (BusinessRuleException invalid) { throw invalid; }
        catch (Exception malformed) { throw invalid(); }
    }
    private static String partyVat(Element root, String side) {
        var party = one(one(root, CAC, side), CAC, "Party");
        String vat = null;
        for (Element scheme : children(party, CAC, "PartyTaxScheme")) {
            if ("VAT".equals(text(one(scheme, CAC, "TaxScheme"), CBC, "ID"))) {
                if (vat != null) throw invalid();
                vat = text(scheme, CBC, "CompanyID").toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            }
        }
        return vat;
    }
    private static BigDecimal money(Element parent, String name) {
        Element element = one(parent, CBC, name);
        if (!"EUR".equals(element.getAttribute("currencyID"))) throw invalid();
        return decimal(element.getTextContent().strip()).setScale(2, RoundingMode.UNNECESSARY);
    }
    private static BigDecimal decimal(String value) {
        // UBL decimal values are plain decimals. Bound precision before arithmetic/setScale so a
        // tiny exponent expression cannot force an enormous allocation during automatic ingestion.
        if (value.length() > 24 || !value.matches("-?[0-9]{1,16}(?:\\.[0-9]{1,6})?")) throw invalid();
        return new BigDecimal(value);
    }
    private static String text(Element parent, String namespace, String name) { return one(parent, namespace, name).getTextContent().strip(); }
    private static Element one(Element parent, String namespace, String name) {
        var found = children(parent, namespace, name);
        if (found.size() != 1) throw invalid();
        return found.getFirst();
    }
    private static List<Element> children(Element parent, String namespace, String name) {
        var found = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI()) && name.equals(element.getLocalName())) found.add(element);
        }
        return found;
    }
    private static BusinessRuleException invalid() { return new BusinessRuleException("Dit Peppol-document is niet eenduidig herkend als TICA Brussel-weekafrekening. Controleer het document in Odoo."); }
}
