package be.enrosed.inventory.application;

import be.enrosed.inventory.adapter.out.persistence.StockClosingArticleEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingContainerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingDecisionEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLineEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingLotEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingMovementEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingSeparateEntity;
import be.enrosed.inventory.adapter.out.persistence.StockClosingWriteDownEntity;
import be.enrosed.inventory.application.ClosingNotices.Notice;
import be.enrosed.inventory.application.ClosingReportData.CompanyIdentity;
import be.enrosed.inventory.domain.ValuationRuleText;
import be.enrosed.shared.company.CompanyProfile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workbook from a fixed report: the product of example 3.8 on its three
 * containers, the container of example 2.12 with its two lots, one container
 * on the water, one invoice that was not afgepunt and two older ones.
 */
public class StockClosingWorkbookTest {

    private static final BigDecimal CENT = new BigDecimal("0.01");

    /* ------------------------------------------------------ company identity */

    @Test
    void theCompanyIsNamedByItsLegalNameAndItsNonBlankAddressParts() {
        CompanyIdentity legal = ClosingReportData.companyIdentity(profile("Enrosed", "Enrosed BV", "BE 1034.273.386",
                "Vekeblok 17", "2400", "Mol", "BE"));
        assertEquals("Enrosed BV", legal.name());
        assertEquals("BE 1034.273.386", legal.vat());
        assertEquals("Vekeblok 17, 2400 Mol, BE", legal.address());

        CompanyIdentity trade = ClosingReportData.companyIdentity(profile("Enrosed", " ", null, " ", "2400", null, "BE"));
        assertEquals("Enrosed", trade.name(), "the trade name when the legal name is blank");
        assertNull(trade.vat());
        assertEquals("2400, BE", trade.address(), "blank parts are left out");
        assertEquals("Mol", ClosingReportData.companyIdentity(profile("E", "E", null, null, null, "Mol", "")).address());
        assertEquals(500, ClosingReportData.companyIdentity(profile("E", "E", null, "x".repeat(600), "1", "A", "BE")).address().length());
    }

    @Test
    void aConceptPrintsTheCompanyItIsGivenAndAFinalClosingTheOneItWasFrozenWith() {
        var view = new StockClosingService.View(sample(true, false).closing(), null, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(),
                List.of(), false, false);
        CompanyProfile live = profile("Handelsnaam", "Live BV", "BE 0000.000.001", "Straat 1", "1000", "Brussel", "BE");
        assertEquals(new CompanyIdentity("Live BV", "BE 0000.000.001", "Straat 1, 1000 Brussel, BE"),
                ClosingReportData.of(view, live).company());

        var frozen = new StockClosingService.View(sample(false, false).closing(), null, null, null, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), false, true);
        assertEquals(new CompanyIdentity("Enrosed BV", "BE 1034.273.386", "Vekeblok 17, 2400 Mol, BE"),
                ClosingReportData.of(frozen, live).company(), "the live profile is not read for a final closing");
    }

    /* ------------------------------------------------------ sheets and heads */

    @Test
    void theSheetsCarryTheNamesAndHeadersTheAccountantIsPromised() {
        Map<String, List<List<Object>>> book = read(new StockClosingWorkbook().render(sample(false, true)));
        assertEquals(List.of("Samenvatting", "Voorraad per locatie", "Producten", "Partijen (FIFO)", "Containers",
                "Containerlijnen", "Betalingen", "Creditnota's", "Geschatte kosten", "Waardeverminderingen", "Partner en derden",
                "Onderweg", "Gefactureerd niet afgepunt", "Telverschillen", "Bewegingen", "Wijzigingen", "Beslissingen",
                "Aandachtspunten", "Waarderingsregel"), List.copyOf(book.keySet()));
        assertFalse(read(new StockClosingWorkbook().render(sample(false, false))).containsKey("Wijzigingen"),
                "only a correction has the sheet of changes");

        assertEquals(ClosingReportData.LOCATION_NOTE, book.get("Voorraad per locatie").get(0).get(0));
        assertEquals(List.of("Boekjaar", "Afsluitdatum", "Locatie", "SKU", "Product", "Categorie", "Eenheid", "Stuks per display",
                "Soort", "Geteld", "Geteld door", "Geteld op", "Correctie naar afsluitdatum", "Aantal op afsluitdatum (alle stuks)",
                "Waarvan eigen voorraad", "Goederen (leverancier)", "Transport via leverancier (CIF)", "Douane & transport",
                "Inspectie & andere kosten", "Beginwaarde", "Aanschafwaarde", "Waarvan geschat", "Waardevermindering", "Waarde"),
                book.get("Voorraad per locatie").get(1));
        assertEquals(List.of("SKU", "Product", "Categorie", "Eenheid", "Soort", "Aantal totaal", "Goederen van derden", "Partner",
                "Gefactureerd uit", "Eigen aantal", "Zonder waarde", "Waarde per stuk (gem.)", "Goederen (leverancier)",
                "Transport via leverancier (CIF)", "Douane & transport", "Inspectie & andere kosten", "Beginwaarde",
                "Aanschafwaarde", "Waardevermindering", "Waarde"), book.get("Producten").get(0));
        assertEquals(List.of("SKU", "Product", "Blok", "Volgorde", "Bron", "Container", "Ontvangen op", "Bron beginwaarde",
                "Aantal in partij", "Aantal gebruikt", "Waarde per stuk", "Goederen per stuk", "Transport per stuk",
                "Douane & transport per stuk", "Inspectie per stuk", "Waarde", "Waarvan geschat", "Aantal met waardevermindering",
                "Waardevermindering"), book.get("Partijen (FIFO)").get(0));
        List<Object> containers = book.get("Containers").get(0);
        assertEquals(39, containers.size(), "fourteen facts, six columns for each of the three payees, seven amounts");
        assertEquals(List.of("Container", "Naam", "Leverancier", "Incoterm leverancier (fiche)", "Rol", "Ontvangen op",
                "Koers geldt tot", "Bron van die datum", "Koers CNY/USD", "Koers USD/EUR goederen", "Koers USD/EUR transport",
                "Transport via leverancier (CIF)", "Verdeelsleutels", "Opmerkingen"), containers.subList(0, 14));
        assertEquals(List.of("Leverancier: Status", "Leverancier: Afspraak", "Leverancier: Betaald", "Leverancier: Nog open",
                "Leverancier: Opgenomen", "Leverancier: Waarvan geschat"), containers.subList(14, 20));
        assertEquals("Douane & transport: Opgenomen", containers.get(24));
        assertEquals("Inspectie & andere kosten: Waarvan geschat", containers.get(31));
        assertEquals(List.of("Prijscreditnota's (verlagen de waarde)", "Tegoed buiten de voorraadwaarde",
                "Koersverschil (buiten waarde)", "Bank- en betalingskosten e.a. onder 'Bijkomende kosten' (buiten waarde)",
                "Enrosed kost (buiten waarde)", "Aanschafwaarde", "Waarvan geschat"), containers.subList(32, 39));
        assertEquals(ClosingReportData.LOT_NOTE, book.get("Containerlijnen").get(0).get(0));
        assertEquals("Aandeel van een partij = bedrag van de container x sleutel van de partij / som van de sleutels van de container.",
                ClosingReportData.LOT_NOTE);
        assertEquals(List.of("Container", "SKU", "Product", "Besteld", "Ontvangen", "Beschadigd", "Later gemeld",
                "Aangerekend aantal", "Deler goederen", "Deler containerkosten", "Bruikbaar in partij", "Inkoopprijs per stuk (EUR)",
                "Sleutel goederen", "Sleutel transport leverancier", "Sleutel douane & transport", "Sleutel inspectie", "Goederen",
                "Prijscreditnota", "Transport via leverancier", "Douane & transport", "Inspectie & andere kosten",
                "Kost van de partij", "Goederen per stuk", "Transport per stuk", "Douane & transport per stuk", "Inspectie per stuk",
                "Waarde per stuk", "Geschat per stuk", "Volgens berekening, ter info: Lokale kosten bij vertrek",
                "Volgens berekening, ter info: Zeevracht", "Volgens berekening, ter info: Invoerrechten",
                "Volgens berekening, ter info: Kosten na aankomst", "Volgens berekening, ter info: Invoerrecht %",
                "Waarde per stuk vorige afsluiting", "Status"), book.get("Containerlijnen").get(1));
        assertEquals(List.of("Container", "Datum", "Betaalstroom", "Omschrijving", "Bedrag", "Munt", "Geboekte eurowaarde",
                "Getelde eurowaarde", "In de waarde", "Regel"), book.get("Betalingen").get(0));
        assertEquals(List.of("Container", "Datum", "Reden", "Bedrag", "Munt", "Eurowaarde", "Behandeling", "Beslist door",
                "Reden van de beslissing"), book.get("Creditnota's").get(0));
        assertEquals(List.of("Container", "Betaalstroom", "Afspraak", "Betaald", "Nog open", "Opgenomen", "waarvan geschat", "Basis"),
                book.get("Geschatte kosten").get(0));
        assertEquals(List.of("Product", "Partij", "Aantal", "Aanschafwaarde per stuk", "Marktwaarde per stuk", "Waardevermindering",
                "Reden", "Toelichting", "Door", "Op"), book.get("Waardeverminderingen").get(0));
        assertEquals(List.of("Container", "Partner", "Product", "Aantal", "Waarde per stuk", "Waarde", "Opgenomen", "Reden"),
                book.get("Partner en derden").get(0));
        assertEquals(List.of("Eigenaar", "Product", "Aantal", "Reden"), below(book.get("Partner en derden"), "Goederen van derden"));
        assertEquals(List.of("Container", "Leverancier", "Incoterm leverancier (fiche)", "Transport via leverancier", "Afvaart",
                "Betaald t/m afsluitdatum", "Waarde", "Opgenomen", "Eigendom of risico vanaf", "Reden"), book.get("Onderweg").get(0));
        assertEquals(List.of("Factuur", "Datum", "Klant", "Product", "Aantal", "Waarde", "Beslissing", "Reden"),
                book.get("Gefactureerd niet afgepunt").get(0));
        assertEquals(List.of("Locatie", "Product", "Volgens systeem", "Geteld", "Verschil", "Reden", "Toelichting", "Geteld door",
                "Tijdstip"), book.get("Telverschillen").get(0));
        assertEquals(List.of("Locatie", "Product", "Geboekt op", "Soort", "Referentie", "Aantal", "Meegerekend", "Reden"),
                book.get("Bewegingen").get(0));
        assertEquals("Wijzigingen tegenover versie 1", book.get("Wijzigingen").get(0).get(0));
        assertEquals(List.of("Soort", "Onderwerp", "Gegeven", "Oud", "Nieuw", "Verschil"), book.get("Wijzigingen").get(1));
        assertEquals(List.of("Soort", "Onderwerp", "Keuze", "Aantal", "Bedrag", "Datum", "Reden", "Door", "Op"),
                book.get("Beslissingen").get(0));
        assertEquals(List.of("Soort", "Tekst"), book.get("Aandachtspunten").get(0));
    }

    @Test
    void theSummaryNamesTheFiguresTheClosingAndItsFingerprint() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<List<Object>> rows = read(new StockClosingWorkbook().render(sample(false, false))).get("Samenvatting");
        for (List<Object> row : rows.subList(1, rows.size())) summary.put((String) row.get(0), row.size() > 1 ? row.get(1) : null);
        assertEquals(List.of("Aanschafwaarde eigen voorraad", "Waardeverminderingen", "Eigen voorraad na waardevermindering",
                "waarvan demostukken", "Partnercontainers opgenomen", "Partnercontainers niet opgenomen",
                "Goederen onderweg opgenomen", "Goederen onderweg niet opgenomen", "Gefactureerd, uit eigen voorraad gehaald",
                "Goederen van derden (aantal, zonder waarde)", "Totaal voorraadwaarde volgens de genomen beslissingen",
                "waarvan op geschatte kosten",
                "Op opgenomen partnercontainers en goederen onderweg (€ 1.840,00) is geen lagere marktwaarde ingevoerd;"
                        + " het ERP voorziet daar geen waardevermindering.",
                "Boekjaar", "Afsluitdatum", "Versie", "Status", "Methode", "Ondertekenaar", "Definitief op", "Gegevenscontrole"),
                List.copyOf(summary.keySet()));
        assertMoney("3181.76", summary.get("Aanschafwaarde eigen voorraad"));
        assertMoney("24.54", summary.get("Waardeverminderingen"));
        assertMoney("3157.22", summary.get("Eigen voorraad na waardevermindering"));
        assertMoney("1840.00", summary.get("Goederen onderweg opgenomen"));
        assertMoney("69.00", summary.get("Gefactureerd, uit eigen voorraad gehaald"));
        assertMoney("4997.22", summary.get("Totaal voorraadwaarde volgens de genomen beslissingen"));
        assertMoney("188.33", summary.get("waarvan op geschatte kosten"));
        assertMoney("2026", summary.get("Boekjaar"));
        assertEquals(LocalDate.of(2026, 12, 31), summary.get("Afsluitdatum"));
        assertEquals("Definitief", summary.get("Status"));
        assertEquals("FIFO per ontvangen partij", summary.get("Methode"));
        assertEquals("Emre Yilmaz", summary.get("Ondertekenaar"));
        assertEquals("d".repeat(64), summary.get("Gegevenscontrole"));
        assertEquals("CONCEPT, niet definitief", read(new StockClosingWorkbook().render(sample(true, false))).get("Samenvatting")
                .stream().filter(row -> "Status".equals(row.get(0))).findFirst().orElseThrow().get(1));
    }

    /* ---------------------------------------------------------------- totals */

    @Test
    void theSheetsAddUpToTheClosingAndEveryShareFollowsFromItsKey() {
        ClosingReportData data = sample(false, false);
        assertWorkbookAddsUp(new StockClosingWorkbook().render(data), data.closing());

        /* The figures of the examples themselves, as the accountant reads them off the sheets. */
        Map<String, List<List<Object>>> book = read(new StockClosingWorkbook().render(data));
        List<Map<String, Object>> locations = table(book.get("Voorraad per locatie"), "Boekjaar");
        assertEquals(List.of("Magazijn", "Verkooppunt TICA", "Totaal"), locations.stream().map(row -> row.get("Locatie")).toList());
        assertMoney("1221", locations.get(0).get("Waarvan eigen voorraad"));
        assertMoney("49", locations.get(1).get("Waarvan eigen voorraad"));
        assertMoney("3059.38", locations.get(0).get("Aanschafwaarde"));
        assertMoney("122.38", locations.get(1).get("Aanschafwaarde"));
        assertMoney("3181.76", locations.get(2).get("Aanschafwaarde"));
        assertMoney("1300", locations.get(2).get("Aantal op afsluitdatum (alle stuks)"));
        Map<String, Object> product = table(book.get("Producten"), "SKU").get(0);
        assertMoney("2.5053", product.get("Waarde per stuk (gem.)"));
        assertMoney("1270", product.get("Eigen aantal"));
        assertMoney("30", product.get("Gefactureerd uit"));
        List<Map<String, Object>> lots = table(book.get("Containerlijnen"), "Container");
        assertMoney("2.5449", lots.get(0).get("Waarde per stuk"));
        assertMoney("0.2025", lots.get(0).get("Geschat per stuk"));
        assertMoney("930", lots.get(0).get("Bruikbaar in partij"));
        assertMoney("620.00", lots.get(0).get("Sleutel douane & transport"));
        assertMoney("1748.00", lots.get(0).get("Sleutel inspectie"));
        assertMoney("5.4446", lots.get(1).get("Waarde per stuk"));
        assertMoney("830.00", lots.get(1).get("Sleutel douane & transport"));
        assertEquals("In orde", lots.get(1).get("Status"));
        List<Map<String, Object>> layers = table(book.get("Partijen (FIFO)"), "SKU");
        assertEquals(List.of("Partij", "Partij", "Partij", "Partij"), layers.stream().map(row -> row.get("Bron")).toList());
        assertMoney("2366.76", layers.get(0).get("Waarde"));
        assertMoney("24.54", layers.get(0).get("Waardevermindering"));
        assertEquals("Gefactureerd, uit eigen voorraad", layers.get(3).get("Blok"));
        assertMoney("69.00", layers.get(3).get("Waarde"));
        Map<String, Object> estimated = table(book.get("Geschatte kosten"), "Container").get(0);
        assertEquals("Douane & transport", estimated.get("Betaalstroom"));
        assertMoney("450.00", estimated.get("waarvan geschat"));
        assertEquals("Afspraak op de container", estimated.get("Basis"));
    }

    /**
     * The sums the accountant can redo on any workbook of a closing: both stock sheets meet the
     * closing, the own pieces per location meet the product, every lot share follows from the key
     * of the lot, the unit components add up, and the three payees less the price credits are the
     * acquisition value of the container.
     */
    public static void assertWorkbookAddsUp(byte[] workbook, StockClosingEntity closing) {
        Map<String, List<List<Object>>> book = read(workbook);
        List<Map<String, Object>> locations = table(book.get("Voorraad per locatie"), "Boekjaar");
        List<Map<String, Object>> products = table(book.get("Producten"), "SKU");
        Map<String, Object> locationTotal = locations.removeLast();
        Map<String, Object> productTotal = products.removeLast();
        assertEquals("Totaal", locationTotal.get("Locatie"));
        assertEquals("Totaal", productTotal.get("Product"));
        assertSame(closing.costValueEur, number(locationTotal.get("Aanschafwaarde")), "total of Voorraad per locatie");
        assertSame(closing.costValueEur, number(productTotal.get("Aanschafwaarde")), "total of Producten");
        assertSame(closing.costValueEur, sum(locations, "Aanschafwaarde"), "the location rows");
        assertSame(closing.costValueEur, sum(products, "Aanschafwaarde"), "the product rows");
        assertSame(closing.writeDownEur, number(productTotal.get("Waardevermindering")), "write-downs on Producten");
        assertSame(closing.ownValueEur, number(locationTotal.get("Waarde")), "value on Voorraad per locatie");
        assertSame(BigDecimal.valueOf(closing.ownQuantity), sum(locations, "Waarvan eigen voorraad"), "own pieces over the locations");
        for (Map<String, Object> product : products) {
            BigDecimal own = sum(locations.stream().filter(row -> product.get("SKU").equals(row.get("SKU"))).toList(),
                    "Waarvan eigen voorraad");
            assertSame(number(product.get("Eigen aantal")), own, "own pieces of " + product.get("Product"));
            assertSame(number(product.get("Aanschafwaarde")), number(product.get("Goederen (leverancier)"))
                    .add(number(product.get("Transport via leverancier (CIF)"))).add(number(product.get("Douane & transport")))
                    .add(number(product.get("Inspectie & andere kosten"))).add(number(product.get("Beginwaarde"))),
                    "components of " + product.get("Product"));
        }

        List<Map<String, Object>> containers = table(book.get("Containers"), "Container");
        List<Map<String, Object>> lots = table(book.get("Containerlijnen"), "Container");
        for (Map<String, Object> container : containers) {
            String name = (String) container.get("Naam");
            BigDecimal included = BigDecimal.ZERO;
            for (String payee : List.of("Leverancier", "Douane & transport", "Inspectie & andere kosten")) {
                BigDecimal paid = number(container.get(payee + ": Betaald"));
                BigDecimal taken = number(container.get(payee + ": Opgenomen"));
                BigDecimal unpaid = taken.subtract(paid);
                assertTrue(unpaid.signum() >= 0, name + " " + payee + ": Opgenomen = Betaald + the unpaid amount");
                assertTrue(number(container.get(payee + ": Waarvan geschat")).compareTo(unpaid) <= 0,
                        name + " " + payee + ": the estimate is part of the unpaid amount");
                included = included.add(taken);
            }
            List<Map<String, Object>> own = lots.stream().filter(lot -> name.equals(lot.get("Container"))).toList();
            if (own.isEmpty()) continue;
            assertSame(number(container.get("Aanschafwaarde")),
                    included.subtract(number(container.get("Prijscreditnota's (verlagen de waarde)"))),
                    name + ": the three payees less the price credits");
            assertSame(number(container.get("Aanschafwaarde")), sum(own, "Kost van de partij"), name + ": the lots");
            BigDecimal logistics = number(container.get("Douane & transport: Opgenomen"));
            BigDecimal separate = number(container.get("Inspectie & andere kosten: Opgenomen"));
            BigDecimal transport = sum(own, "Transport via leverancier");
            BigDecimal goods = number(container.get("Leverancier: Opgenomen")).subtract(transport);
            assertSame(goods, sum(own, "Goederen"), name + ": goods over the lots");
            assertSame(logistics, sum(own, "Douane & transport"), name + ": Douane & transport over the lots");
            assertSame(separate, sum(own, "Inspectie & andere kosten"), name + ": inspection over the lots");
            for (Map<String, Object> lot : own) {
                assertShare(goods, lot, "Sleutel goederen", sum(own, "Sleutel goederen"), "Goederen");
                assertShare(transport, lot, "Sleutel transport leverancier", sum(own, "Sleutel transport leverancier"),
                        "Transport via leverancier");
                assertShare(logistics, lot, "Sleutel douane & transport", sum(own, "Sleutel douane & transport"), "Douane & transport");
                assertShare(separate, lot, "Sleutel inspectie", sum(own, "Sleutel inspectie"), "Inspectie & andere kosten");
                assertSame(number(lot.get("Waarde per stuk")), number(lot.get("Goederen per stuk"))
                        .add(number(lot.get("Transport per stuk"))).add(number(lot.get("Douane & transport per stuk")))
                        .add(number(lot.get("Inspectie per stuk"))), name + " " + lot.get("Product") + ": unit components");
            }
        }
        for (Map<String, Object> layer : table(book.get("Partijen (FIFO)"), "SKU")) {
            if (layer.get("Goederen per stuk") == null) continue;
            assertSame(number(layer.get("Waarde per stuk")), number(layer.get("Goederen per stuk"))
                    .add(number(layer.get("Transport per stuk"))).add(number(layer.get("Douane & transport per stuk")))
                    .add(number(layer.get("Inspectie per stuk"))), "unit components of layer " + layer.get("Container"));
        }
    }

    private static void assertShare(BigDecimal amount, Map<String, Object> lot, String keyColumn, BigDecimal keys, String shareColumn) {
        BigDecimal share = number(lot.get(shareColumn));
        if (keys.signum() == 0) {
            assertEquals(0, amount.signum() == 0 ? share.signum() : 0, shareColumn + " without keys");
            return;
        }
        BigDecimal exact = amount.multiply(number(lot.get(keyColumn))).divide(keys, 6, RoundingMode.HALF_UP);
        assertTrue(exact.subtract(share).abs().compareTo(CENT) <= 0, lot.get("Product") + " " + shareColumn + ": " + share
                + " should be " + amount + " x " + lot.get(keyColumn) + " / " + keys + " = " + exact + " within a cent");
    }

    /* ----------------------------------------------------------------- lists */

    @Test
    void theOlderInvoicesStandUnderTheirOwnHeadingAndTheListsSayWhatWasDecided() {
        ClosingReportData data = sample(false, true);
        assertEquals("Oudere facturen zonder afpunten: 2, niet verwerkt.", data.olderInvoiceLine());
        assertNull(new ClosingReportData(data.closing(), data.company(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), null).olderInvoiceLine());

        Map<String, List<List<Object>>> book = read(new StockClosingWorkbook().render(data));
        List<List<Object>> invoiced = book.get("Gefactureerd niet afgepunt");
        Map<String, Object> out = table(invoiced, "Factuur").get(0);
        assertEquals("F-2026-118", out.get("Factuur"));
        assertEquals(LocalDate.of(2026, 12, 20), out.get("Datum"));
        assertEquals("Bloemen Peeters", out.get("Klant"));
        assertMoney("30", out.get("Aantal"));
        assertMoney("69.00", out.get("Waarde"));
        assertEquals("Uit eigen voorraad", out.get("Beslissing"));
        assertEquals(List.of("Factuur", "Datum", "Klant", "Aantal"),
                below(invoiced, "Oudere facturen zonder afpunten, niet verwerkt"));
        assertEquals(List.of("F-2024-007", "F-2025-031"), invoiced.subList(invoiced.size() - 2, invoiced.size()).stream()
                .map(row -> row.get(0)).toList());

        Map<String, Object> transit = table(book.get("Onderweg"), "Container").get(0);
        assertEquals("ja", transit.get("Opgenomen"));
        assertEquals("FOB Ningbo", transit.get("Incoterm leverancier (fiche)"));
        assertEquals("nee", transit.get("Transport via leverancier"));
        assertEquals(LocalDate.of(2026, 12, 15), transit.get("Eigendom of risico vanaf"));
        assertMoney("1840.00", transit.get("Waarde"));

        List<Map<String, Object>> payments = table(book.get("Betalingen"), "Container");
        assertEquals(5, payments.size(), "only the containers this closing values");
        assertEquals("Bank- en betalingskosten e.a. onder 'Bijkomende kosten'", payments.get(3).get("Betaalstroom"));
        assertEquals("nee", payments.get(3).get("In de waarde"));
        assertMoney("2576.00", payments.get(1).get("Getelde eurowaarde"));
        assertMoney("2604.00", payments.get(1).get("Geboekte eurowaarde"));
        List<Map<String, Object>> credits = table(book.get("Creditnota's"), "Container");
        assertEquals(List.of("verlaagt de aanschafwaarde", "buiten de voorraadwaarde"),
                credits.stream().map(row -> row.get("Behandeling")).toList());

        List<Map<String, Object>> movements = table(book.get("Bewegingen"), "Locatie");
        assertEquals(List.of("ja", "Verwijderd uit de voorraadgeschiedenis"), movements.stream().map(row -> row.get("Meegerekend")).toList());
        Map<String, Object> difference = table(book.get("Telverschillen"), "Locatie").get(0);
        assertEquals("Beschadigd of stuk", difference.get("Reden"));
        assertMoney("-2", difference.get("Verschil"));

        List<Map<String, Object>> changes = table(book.get("Wijzigingen"), "Soort");
        assertEquals("Totaal voorraadwaarde", changes.get(0).get("Onderwerp"));
        assertMoney("4990.00", changes.get(0).get("Oud"));
        assertMoney("4997.22", changes.get(0).get("Nieuw"));
        assertMoney("7.22", changes.get(0).get("Verschil"));
        assertEquals(List.of("Totaal", "Product", "Partij", "Beweging", "Beginwaarde", "Beginwaarde"),
                changes.stream().map(row -> row.get("Soort")).toList(), "one row per figure that differs");
        assertEquals(List.of("Aanschafwaarde", "Waarde per stuk", "Effect", "Aantal", "Waarde per stuk"),
                changes.subList(1, 6).stream().map(row -> row.get("Gegeven")).toList());
        assertEquals("niet in de lijst", changes.get(3).get("Nieuw"));

        List<Map<String, Object>> decisions = table(book.get("Beslissingen"), "Soort");
        assertEquals(List.of("Bevestiging btw", "Waardevermindering", "Gefactureerd, nog niet afgepunt", "Goederen onderweg"),
                decisions.stream().map(row -> row.get("Soort")).toList());
        assertEquals("Beschadigd · Doos ingedeukt", decisions.get(1).get("Reden"));
        assertEquals("Opgenomen", decisions.get(3).get("Keuze"));
        assertEquals(List.of("Aandacht", "Aandacht"), table(book.get("Aandachtspunten"), "Soort").stream().map(row -> row.get("Soort")).toList());
        List<List<Object>> rule = book.get("Waarderingsregel");
        assertEquals(ValuationRuleText.render(2026), rule.get(1).get(0));
        assertEquals(List.of("Methode", "FIFO per ontvangen partij"), rule.get(2));
        assertEquals("Van toepassing sinds", rule.get(3).get(0));
        assertMoney("2026", rule.get(3).get(1));
    }

    /* ----------------------------------------------------------------- words */

    @Test
    void theWordsThatHaveALegalMeaningStandOnlyWhereTheyBelong() {
        for (ClosingReportData data : List.of(sample(false, true), sample(true, false))) {
            List<String> texts = new ArrayList<>();
            read(new StockClosingWorkbook().render(data)).forEach((sheet, rows) -> {
                texts.add(sheet);
                rows.forEach(row -> row.forEach(cell -> { if (cell instanceof String text) texts.add(text); }));
            });
            assertWording(texts, data.closing().ruleText);
        }
    }

    /**
     * "Enrosed kost" only where it is named as outside the value, "Bijkomende kosten" only next to
     * the bank and payment costs or in the sentence of the rule about the costs that are in the
     * value, and the word "Afwaardering" nowhere.
     */
    public static void assertWording(List<String> texts, String ruleText) {
        assertTrue(texts.stream().anyMatch(text -> text.contains("Enrosed kost")), "the Enrosed kost is named");
        for (String text : texts) {
            assertFalse(text.toLowerCase().contains("afwaardering"), "\"Afwaardering\" in: " + text);
            if (text.equals(ruleText)) continue;
            String outside = text.replace(ruleText, "");
            if (outside.contains("Enrosed kost")) {
                assertTrue(outside.contains("Enrosed kost (buiten waarde)"), "\"Enrosed kost\" outside a buiten-waarde context: " + text);
            }
            if (outside.toLowerCase().contains("bijkomende kosten")) {
                assertTrue(outside.toLowerCase().contains("bank- en betalingskosten"), "\"Bijkomende kosten\" alone: " + text);
            }
        }
        assertTrue(ruleText.contains("De bijkomende kosten van de aankoop (vervoer, invoerrechten, douane- en aankomstkosten,"
                + " inspectie) maken dus deel uit van de aanschafwaarde."));
        assertTrue(ruleText.contains("bank- en betalingskosten en andere kosten die niet bij de zending horen"
                + " (in het ERP geboekt onder \"Bijkomende kosten\")"));
    }

    /* --------------------------------------------------------------- reading */

    /** Every sheet as rows of cell values: text, a decimal for a number, a day for a date cell. */
    public static Map<String, List<List<Object>>> read(byte[] bytes) {
        Map<String, List<List<Object>>> book = new LinkedHashMap<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                List<List<Object>> rows = new ArrayList<>();
                for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                    Row row = sheet.getRow(index);
                    List<Object> cells = new ArrayList<>();
                    for (int column = 0; row != null && column < row.getLastCellNum(); column++) {
                        Cell cell = row.getCell(column);
                        if (cell == null || cell.getCellType() == CellType.BLANK) cells.add(null);
                        else if (cell.getCellType() == CellType.NUMERIC) {
                            cells.add(DateUtil.isCellDateFormatted(cell)
                                    ? (cell.getLocalDateTimeCellValue().toLocalTime().toSecondOfDay() == 0
                                            ? cell.getLocalDateTimeCellValue().toLocalDate() : cell.getLocalDateTimeCellValue())
                                    : BigDecimal.valueOf(cell.getNumericCellValue()));
                        } else cells.add(cell.getStringCellValue());
                    }
                    while (!cells.isEmpty() && cells.getLast() == null) cells.removeLast();
                    rows.add(cells);
                }
                book.put(sheet.getSheetName(), rows);
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return book;
    }

    /** The first table of a sheet as one map per row, keyed by its header; the header row starts with the given title. */
    public static List<Map<String, Object>> table(List<List<Object>> sheet, String firstHeader) {
        int header = 0;
        while (sheet.get(header).isEmpty() || !firstHeader.equals(sheet.get(header).get(0)) || sheet.get(header).size() < 2) header++;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Object> cells : sheet.subList(header + 1, sheet.size())) {
            if (cells.isEmpty()) break;
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 0; column < sheet.get(header).size(); column++) {
                row.put((String) sheet.get(header).get(column), column < cells.size() ? cells.get(column) : null);
            }
            rows.add(row);
        }
        return rows;
    }

    /** The header row of the table that follows a sub-heading on a sheet. */
    private static List<Object> below(List<List<Object>> sheet, String heading) {
        for (int index = 0; index < sheet.size() - 1; index++) {
            if (!sheet.get(index).isEmpty() && heading.equals(sheet.get(index).get(0))) return sheet.get(index + 1);
        }
        throw new AssertionError("no heading " + heading);
    }

    public static BigDecimal number(Object cell) {
        return cell instanceof BigDecimal number ? number : BigDecimal.ZERO;
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String column) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) total = total.add(number(row.get(column)));
        return total;
    }

    public static void assertMoney(String expected, Object actual) {
        assertNotNull(actual, "expected " + expected);
        assertTrue(actual instanceof BigDecimal, "a number cell, not " + actual.getClass().getSimpleName() + " " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), "expected " + expected + " but was " + actual);
    }

    private static void assertSame(BigDecimal expected, BigDecimal actual, String what) {
        assertEquals(0, (expected == null ? BigDecimal.ZERO : expected).compareTo(actual), what + ": expected " + expected + " but was " + actual);
    }

    /* --------------------------------------------------------------- fixture */

    private static CompanyProfile profile(String name, String legalName, String vat, String address, String postalCode,
                                          String city, String country) {
        return new CompanyProfile(name, legalName, vat, "", address, postalCode, city, country, "", "", "", "", "", "", "",
                null, null, null, null);
    }

    /**
     * The fixed report. Product "Roos in stolp rood" of example 3.8: 1.300 pieces, 30 of them
     * invoiced out, on three containers; container PO-2026-014 of example 2.12 with lots A and B;
     * container PO-2026-030 on the water, included at € 1.840,00.
     */
    public static ClosingReportData sample(boolean concept, boolean correction) {
        StockClosingEntity closing = new StockClosingEntity();
        closing.id = correction ? 2L : 1L;
        closing.closingYear = 2026;
        closing.versionNo = correction ? 2 : 1;
        closing.closingDate = LocalDate.of(2026, 12, 31);
        closing.cutoffAt = InventoryClock.cutoffAt(closing.closingDate);
        closing.status = concept ? "CONCEPT" : "DEFINITIEF";
        closing.supersedesId = correction ? 1L : null;
        closing.correctionReason = correction ? "Factuur van de expediteur kwam later binnen" : null;
        closing.ruleMethod = ValuationRuleText.METHOD;
        closing.ruleMethodLabel = ValuationRuleText.METHOD_LABEL;
        closing.ruleVersion = ValuationRuleText.RULE_VERSION;
        closing.ruleEffectiveFromYear = 2026;
        closing.ruleText = ValuationRuleText.render(2026);
        closing.costValueEur = new BigDecimal("3181.76");
        closing.writeDownEur = new BigDecimal("24.54");
        closing.ownValueEur = new BigDecimal("3157.22");
        closing.demoValueEur = new BigDecimal("0.00");
        closing.partnerIncludedEur = new BigDecimal("0.00");
        closing.partnerExcludedEur = new BigDecimal("0.00");
        closing.transitIncludedEur = new BigDecimal("1840.00");
        closing.transitExcludedEur = new BigDecimal("0.00");
        closing.invoicedOutEur = new BigDecimal("69.00");
        closing.totalValueEur = new BigDecimal("4997.22");
        closing.estimatedEur = new BigDecimal("188.33");
        closing.ownQuantity = 1270;
        closing.unvaluedQuantity = 0;
        closing.dataSha256 = "d".repeat(64);
        if (!concept) {
            closing.companyName = "Enrosed BV";
            closing.companyVat = "BE 1034.273.386";
            closing.companyAddress = "Vekeblok 17, 2400 Mol, BE";
            closing.signerName = "Emre Yilmaz";
            closing.finalizedByName = "Emre Yilmaz";
            closing.finalizedAt = Instant.parse("2027-01-15T10:30:00Z");
            closing.xlsxSha256 = "e".repeat(64);
        }

        StockClosingArticleEntity rose = new StockClosingArticleEntity();
        rose.productId = 1L; rose.sku = "RS-R"; rose.productName = "Roos in stolp rood"; rose.categoryName = "Stolpen";
        rose.unitKey = "stuk"; rose.salesUnit = "PIECE"; rose.demo = false; rose.active = true;
        rose.countedQuantity = 1260; rose.rollDelta = 40; rose.closingQuantity = 1300; rose.thirdPartyQuantity = 0;
        rose.partnerQuantity = 0; rose.invoicedOutQuantity = 30; rose.ownQuantity = 1270; rose.unvaluedQuantity = 0;
        rose.costValueEur = new BigDecimal("3181.76"); rose.goodsEur = new BigDecimal("2354.00");
        rose.transportEur = new BigDecimal("0.00"); rose.logisticsEur = new BigDecimal("741.92");
        rose.separateEur = new BigDecimal("85.84"); rose.openingEur = new BigDecimal("0.00");
        rose.estimatedEur = new BigDecimal("188.33"); rose.averageUnitEur = new BigDecimal("2.5053");
        rose.writeDownEur = new BigDecimal("24.54"); rose.ownValueEur = new BigDecimal("3157.22"); rose.status = "OK";

        List<StockClosingLayerEntity> layers = List.of(
                layer("EIGEN", 1, 14L, "PO-2026-014", LocalDate.of(2026, 11, 20), 930, 930, "2.5449", "1.8000", "0.6526", "0.0923",
                        "2366.76", "188.33", null),
                layer("EIGEN", 2, 9L, "PO-2026-009", LocalDate.of(2026, 6, 14), 300, 300, "2.4100", "2.0000", "0.4100", "0.0000",
                        "723.00", "0.00", null),
                layer("EIGEN", 3, 3L, "PO-2026-003", LocalDate.of(2026, 2, 2), 500, 40, "2.3000", "2.0000", "0.3000", "0.0000",
                        "92.00", "0.00", null),
                layer("GEFACTUREERD", 3, 3L, "PO-2026-003", LocalDate.of(2026, 2, 2), null, 30, "2.3000", "2.0000", "0.3000", "0.0000",
                        "69.00", "0.00", 118L));
        layers.get(0).writeDownQuantity = 12;
        layers.get(0).writeDownEur = new BigDecimal("24.54");

        StockClosingLineEntity warehouse = line(1L, "Magazijn", 1210, 40, 1250, "3059.38", "2263.46", "713.38", "82.54", "181.09",
                "23.60", "3035.78");
        warehouse.expectedQuantity = 1212; warehouse.countedQuantity = 1210; warehouse.countDifference = -2;
        warehouse.countReasonCode = "BESCHADIGD"; warehouse.countedByName = "Emre Yilmaz";
        warehouse.countedAt = Instant.parse("2027-01-02T09:15:00Z");
        StockClosingLineEntity shop = line(2L, "Verkooppunt TICA", 50, 0, 50, "122.38", "90.54", "28.54", "3.30", "7.24", "0.94", "121.44");
        shop.expectedQuantity = 50; shop.countedQuantity = 50; shop.countDifference = 0; shop.countedByName = "Sara";

        StockClosingContainerEntity box = new StockClosingContainerEntity();
        box.id = 100L; box.purchaseOrderId = 14L; box.orderNumber = "PO-2026-014"; box.displayName = "PO-2026-014";
        box.supplierName = "Yiwu Roses"; box.supplierIncoterm = "FOB Ningbo"; box.role = "EIGEN";
        box.orderDate = LocalDate.of(2026, 8, 20); box.receivedOn = LocalDate.of(2026, 11, 20);
        box.rateCutoffDate = LocalDate.of(2026, 11, 20); box.rateCutoffSource = "ONTVANGST";
        box.quantityBasis = "ONTVANGEN"; box.billedBasis = "BESTELD"; box.cnyToUsd = new BigDecimal("0.14000000");
        box.usdToEurGoods = new BigDecimal("0.92000000"); box.cif = false; box.groupVariants = false;
        box.separateInPiecePrice = false; box.allocOrigin = "CBM"; box.allocFreight = "CBM"; box.allocDestination = "CBM";
        box.allocSeparate = "SEPARATE";
        box.supplierStatus = "PAID"; box.supplierPlannedEur = new BigDecimal("3680.00"); box.supplierPaidEur = new BigDecimal("3692.00");
        box.supplierOpenEur = new BigDecimal("0.00"); box.supplierIncludedEur = new BigDecimal("3692.00");
        box.supplierEstimatedEur = new BigDecimal("0.00"); box.supplierGoodsEur = new BigDecimal("3692.00");
        box.supplierTransportEur = new BigDecimal("0.00");
        box.logisticsStatus = "PARTIAL"; box.logisticsPlannedEur = new BigDecimal("1450.00"); box.logisticsPaidEur = new BigDecimal("1000.00");
        box.logisticsOpenEur = new BigDecimal("450.00"); box.logisticsIncludedEur = new BigDecimal("1450.00");
        box.logisticsEstimatedEur = new BigDecimal("450.00");
        box.separateStatus = "PAID"; box.separatePlannedEur = new BigDecimal("180.00"); box.separatePaidEur = new BigDecimal("180.00");
        box.separateOpenEur = new BigDecimal("0.00"); box.separateIncludedEur = new BigDecimal("180.00");
        box.separateEstimatedEur = new BigDecimal("0.00");
        box.otherExcludedEur = new BigDecimal("35.00"); box.priceCreditEur = new BigDecimal("92.00");
        box.lossCreditEur = new BigDecimal("92.00"); box.exchangeDifferenceEur = new BigDecimal("28.00");
        box.enrosedCostExcludedEur = new BigDecimal("2000.00"); box.acquisitionEur = new BigDecimal("5230.00");
        box.estimatedEur = new BigDecimal("450.00");
        box.paymentsJson = "[" + payment(1, "2026-09-01", "SUPPLIER", "Leverancier", "voorschot", "1200.00", "USD", "1116.00",
                "1116.00", true, "Geboekte eurowaarde (betaald tot en met de datum van aankoop)")
                + "," + payment(2, "2026-12-05", "SUPPLIER", "Leverancier", "saldo", "2800.00", "USD", "2604.00", "2576.00", true,
                "Koers goederen van de container (betaald na de aankoop)")
                + "," + payment(3, "2026-11-25", "LOGISTICS", "Douane & transport", "expediteur", "1000.00", "EUR", "1000.00",
                "1000.00", true, "Euro")
                + "," + payment(4, "2026-12-05", "OTHER", "Bijkomende kosten", "bankkost", "35.00", "EUR", "35.00", "0.00", false,
                "Niet in de waarde (Bijkomende kosten)")
                + "," + payment(5, "2026-11-22", "SEPARATE", "Inspectie & andere kosten", "inspectie", "180.00", "EUR", "180.00",
                "180.00", true, "Euro") + "]";
        box.creditsJson = "[{\"creditId\":1,\"notedOn\":\"2026-12-01\",\"reasonLabel\":\"Prijsverschil\",\"amount\":100.00,"
                + "\"currency\":\"USD\",\"countedEur\":92.00,\"treatment\":\"VERLAAGT\",\"treatmentLabel\":\"verlaagt de aanschafwaarde\","
                + "\"decisionId\":null,\"decisionRequired\":false,\"reason\":null},"
                + "{\"creditId\":2,\"notedOn\":\"2026-12-01\",\"reasonLabel\":\"Tekort\",\"amount\":100.00,\"currency\":\"USD\","
                + "\"countedEur\":92.00,\"treatment\":\"BUITEN\",\"treatmentLabel\":\"buiten de voorraadwaarde\",\"decisionId\":null,"
                + "\"decisionRequired\":false,\"reason\":null}]";
        StockClosingLotEntity lotA = lot(100L, 14L, 1L, "RS-R", "Roos in stolp rood", 1000, 950, 20, 930, "1.8400", "1840.00",
                "620.00", "1748.00", "1846.00", "46.00", "620.00", "87.69", "2507.69", "192.41", "1.8000", "0.6526", "0.0923",
                "2.5449", "0.2025", "TEKORT");
        StockClosingLotEntity lotB = lot(100L, 14L, 2L, "TL-G", "Tulp in stolp geel", 500, 500, 0, 500, "3.6800", "1840.00",
                "830.00", "1840.00", "1846.00", "46.00", "830.00", "92.31", "2722.31", "257.59", "3.6000", "1.6600", "0.1846",
                "5.4446", "0.5152", "OK");

        StockClosingContainerEntity water = new StockClosingContainerEntity();
        water.id = 101L; water.purchaseOrderId = 30L; water.orderNumber = "PO-2026-030"; water.displayName = "PO-2026-030";
        water.supplierName = "Yiwu Roses"; water.supplierIncoterm = "FOB Ningbo"; water.role = "ONDERWEG";
        water.shippedOn = LocalDate.of(2026, 12, 1); water.rateCutoffDate = LocalDate.of(2026, 12, 15);
        water.rateCutoffSource = "ONDERWEG"; water.quantityBasis = "BESTELD"; water.billedBasis = "BESTELD"; water.cif = false;
        water.usdToEurGoods = new BigDecimal("0.92000000");
        water.supplierStatus = "PAID"; water.supplierPlannedEur = new BigDecimal("1840.00"); water.supplierPaidEur = new BigDecimal("1840.00");
        water.supplierOpenEur = new BigDecimal("0.00"); water.supplierIncludedEur = new BigDecimal("1840.00");
        water.supplierGoodsEur = new BigDecimal("1840.00"); water.acquisitionEur = new BigDecimal("1840.00");
        water.paymentsJson = "[]"; water.creditsJson = "[]";
        StockClosingLotEntity lotWater = lot(101L, 30L, 3L, "RS-W", "Roos in stolp wit", 1000, 1000, 0, 1000, "1.8400", "1840.00",
                null, null, "1840.00", "0.00", "0.00", "0.00", "1840.00", "0.00", "1.8400", "0.0000", "0.0000", "1.8400", "0.0000", "OK");
        lotWater.role = "ONDERWEG";

        StockClosingSeparateEntity invoiced = separate("GEFACTUREERD", "F-2026-118", LocalDate.of(2026, 12, 20), "Bloemen Peeters", 30);
        invoiced.salesOrderId = 118L; invoiced.productId = 1L; invoiced.productName = "Roos in stolp rood";
        invoiced.unitValueEur = new BigDecimal("2.3000"); invoiced.valueEur = new BigDecimal("69.00"); invoiced.choice = "UIT";
        StockClosingSeparateEntity transit = separate("ONDERWEG", "PO-2026-030", LocalDate.of(2026, 10, 1), "Yiwu Roses", 1000);
        transit.purchaseOrderId = 30L; transit.documentName = "PO-2026-030"; transit.productId = 3L; transit.productName = "Roos in stolp wit";
        transit.unitValueEur = new BigDecimal("1.8400"); transit.valueEur = new BigDecimal("1840.00"); transit.included = true;
        transit.ownershipDate = LocalDate.of(2026, 12, 15); transit.shippedOn = LocalDate.of(2026, 12, 1);
        transit.paidUntilClosingEur = new BigDecimal("1840.00"); transit.reason = "FOB: risico over bij het laden";
        List<StockClosingSeparateEntity> separates = List.of(invoiced, transit,
                separate("OUDER", "F-2024-007", LocalDate.of(2024, 3, 4), "Tuincentrum Maes", 12),
                separate("OUDER", "F-2025-031", LocalDate.of(2025, 9, 9), "Bloemen Peeters", 6));

        StockClosingWriteDownEntity down = new StockClosingWriteDownEntity();
        down.decisionId = 2L; down.productId = 1L; down.sku = "RS-R"; down.productName = "Roos in stolp rood"; down.layerPosition = 1;
        down.layerLabel = "PO-2026-014 · ontvangen 20/11/2026"; down.quantity = 12; down.layerUnitEur = new BigDecimal("2.5449");
        down.marketUnitEur = new BigDecimal("0.5000"); down.amountEur = new BigDecimal("24.54"); down.reasonCode = "BESCHADIGD";
        down.reason = "Doos ingedeukt"; down.decidedByName = "Emre Yilmaz"; down.decidedAt = Instant.parse("2027-01-10T08:00:00Z");

        StockClosingMovementEntity sale = movement(501L, "Verkocht", "F-2027-001", -40, true, false);
        StockClosingMovementEntity gone = movement(502L, "Correctie", "telfout", 3, false, true);

        StockClosingDecisionEntity vat = decision(1L, "VAT_CONFIRMATION");
        vat.flag = true;
        StockClosingDecisionEntity writeDown = decision(2L, "WRITE_DOWN");
        writeDown.productId = 1L; writeDown.quantity = 12; writeDown.unitValueEur = new BigDecimal("0.5000");
        writeDown.reasonCode = "BESCHADIGD"; writeDown.reason = "Doos ingedeukt";
        StockClosingDecisionEntity out = decision(3L, "INVOICED");
        out.salesOrderId = 118L; out.choice = "UIT";
        StockClosingDecisionEntity included = decision(4L, "TRANSIT");
        included.purchaseOrderId = 30L; included.flag = true; included.decisionDate = LocalDate.of(2026, 12, 15);
        included.reason = "FOB: risico over bij het laden";

        List<Notice> notices = new ArrayList<>();
        if (concept) notices.add(new Notice("BTW_BEVESTIGING", "BLOCKER", "afsluiten", "Bevestig dat de betalingen onder Leverancier,"
                + " Douane & transport en Inspectie & andere kosten zonder aftrekbare btw zijn ingevoerd.", null, null, null, null,
                null, null, null));
        notices.add(new Notice("GESCHAT", "WARNING", "waarde", "1 containers met geschatte kosten: € 188,33 in de voorraadwaarde.",
                null, null, null, null, null, null, null));
        notices.add(new Notice("BIJKOMENDE_KOSTEN", "WARNING", "waarde", "€ 35,00 bank- en betalingskosten en andere bedragen onder"
                + " 'Bijkomende kosten' zijn niet opgenomen. Hoort een bedrag bij de zending, zet het dan op de container onder"
                + " 'Inspectie & andere kosten'. Btw die je terugkrijgt hoort hier wel.", null, null, null, null, null, null, null));

        ClosingVersionDiff.Changes changes = !correction ? null : new ClosingVersionDiff.Changes(1L, 1, new BigDecimal("4990.00"),
                new BigDecimal("4997.22"),
                List.of(new ClosingVersionDiff.ArticleChange(1L, "RS-R", "Roos in stolp rood", 1300, 1300, new BigDecimal("3174.54"),
                        new BigDecimal("3181.76"), new BigDecimal("24.54"), new BigDecimal("24.54"))),
                List.of(new ClosingVersionDiff.LotChange(14L, "PO-2026-014", 1L, "Roos in stolp rood", new BigDecimal("2.5371"),
                        new BigDecimal("2.5449"))),
                List.of(new ClosingVersionDiff.MovementChange(499L, "Roos in stolp rood", "Magazijn", "F-2027-000", -5, null)),
                List.of(new ClosingVersionDiff.OpeningChange(7L, "Tulp in stolp geel", "Inventaris 2025", 10, new BigDecimal("4.0000"),
                        null, null)));

        return new ClosingReportData(closing, concept ? new CompanyIdentity("Concept BV", "BE 0000.000.097", "Dorpsstraat 1, 9000 Gent, BE")
                : new CompanyIdentity(closing.companyName, closing.companyVat, closing.companyAddress),
                notices, List.of(rose), layers, List.of(warehouse, shop), List.of(box, water), List.of(lotA, lotB, lotWater),
                separates, List.of(down), List.of(sale, gone),
                concept ? List.of(writeDown, out, included) : List.of(vat, writeDown, out, included), Map.of(1L, 2026, 2L, 2026), changes);
    }

    private static StockClosingLayerEntity layer(String block, int position, long purchaseOrderId, String number, LocalDate receivedOn,
                                                 Integer capacity, int quantity, String unit, String goods, String logistics,
                                                 String separate, String value, String estimated, Long salesOrderId) {
        StockClosingLayerEntity layer = new StockClosingLayerEntity();
        layer.productId = 1L; layer.block = block; layer.position = position; layer.source = "PARTIJ";
        layer.purchaseOrderId = purchaseOrderId; layer.orderNumber = number; layer.displayName = number; layer.receivedOn = receivedOn;
        layer.capacity = capacity; layer.quantity = quantity; layer.unitValueEur = new BigDecimal(unit);
        layer.unitGoodsEur = new BigDecimal(goods); layer.unitTransportEur = new BigDecimal("0.0000");
        layer.unitLogisticsEur = new BigDecimal(logistics); layer.unitSeparateEur = new BigDecimal(separate);
        layer.unitEstimatedEur = new BigDecimal("0.0000"); layer.valueEur = new BigDecimal(value);
        layer.estimatedEur = new BigDecimal(estimated); layer.salesOrderId = salesOrderId;
        layer.writeDownQuantity = 0; layer.writeDownEur = new BigDecimal("0.00");
        return layer;
    }

    private static StockClosingLineEntity line(long locationId, String location, int anchor, int roll, int closing, String cost,
                                               String goods, String logistics, String separate, String estimated, String writeDown,
                                               String value) {
        StockClosingLineEntity line = new StockClosingLineEntity();
        line.productId = 1L; line.sku = "RS-R"; line.productName = "Roos in stolp rood"; line.locationId = locationId;
        line.locationName = location; line.anchor = "TELLING"; line.anchorQuantity = anchor; line.rollDelta = roll;
        line.closingQuantity = closing; line.costValueEur = new BigDecimal(cost); line.goodsEur = new BigDecimal(goods);
        line.transportEur = new BigDecimal("0.00"); line.logisticsEur = new BigDecimal(logistics);
        line.separateEur = new BigDecimal(separate); line.openingEur = new BigDecimal("0.00");
        line.estimatedEur = new BigDecimal(estimated); line.writeDownEur = new BigDecimal(writeDown);
        line.ownValueEur = new BigDecimal(value);
        return line;
    }

    private static StockClosingLotEntity lot(long containerId, long purchaseOrderId, long productId, String sku, String name,
                                             int ordered, int received, int damaged, int capacity, String price, String goodsKey,
                                             String logisticsKey, String separateKey, String goods, String credit, String logistics,
                                             String separate, String cost, String estimated, String unitGoods, String unitLogistics,
                                             String unitSeparate, String unit, String unitEstimated, String status) {
        StockClosingLotEntity lot = new StockClosingLotEntity();
        lot.containerId = containerId; lot.purchaseOrderId = purchaseOrderId; lot.role = "EIGEN"; lot.productId = productId;
        lot.sku = sku; lot.productName = name; lot.orderedQuantity = ordered; lot.receivedQuantity = received;
        lot.damagedQuantity = damaged; lot.laterLostQuantity = 0; lot.billedQuantity = ordered; lot.goodsDivisor = ordered;
        lot.costDivisor = received; lot.capacity = capacity; lot.unitPriceEur = new BigDecimal(price);
        lot.goodsKeyEur = new BigDecimal(goodsKey);
        lot.logisticsKeyEur = logisticsKey == null ? null : new BigDecimal(logisticsKey);
        lot.separateKeyEur = separateKey == null ? null : new BigDecimal(separateKey);
        lot.goodsEur = new BigDecimal(goods); lot.priceCreditEur = new BigDecimal(credit); lot.transportEur = new BigDecimal("0.00");
        lot.logisticsEur = new BigDecimal(logistics); lot.separateEur = new BigDecimal(separate); lot.lotCostEur = new BigDecimal(cost);
        lot.estimatedEur = new BigDecimal(estimated); lot.unitGoodsEur = new BigDecimal(unitGoods);
        lot.unitTransportEur = new BigDecimal("0.0000"); lot.unitLogisticsEur = new BigDecimal(unitLogistics);
        lot.unitSeparateEur = new BigDecimal(unitSeparate); lot.unitValueEur = new BigDecimal(unit);
        lot.unitEstimatedEur = new BigDecimal(unitEstimated); lot.status = status;
        return lot;
    }

    private static String payment(int id, String paidOn, String payee, String payeeLabel, String label, String amount,
                                  String currency, String stored, String counted, boolean inValue, String rule) {
        return "{\"paymentId\":" + id + ",\"paidOn\":\"" + paidOn + "\",\"payee\":\"" + payee + "\",\"payeeLabel\":\"" + payeeLabel
                + "\",\"label\":\"" + label + "\",\"amount\":" + amount + ",\"currency\":\"" + currency + "\",\"storedEur\":" + stored
                + ",\"countedEur\":" + counted + ",\"inValue\":" + inValue + ",\"rule\":\"" + rule + "\"}";
    }

    private static StockClosingSeparateEntity separate(String kind, String number, LocalDate date, String counterparty, int quantity) {
        StockClosingSeparateEntity row = new StockClosingSeparateEntity();
        row.kind = kind; row.documentNumber = number; row.documentDate = date; row.counterparty = counterparty; row.quantity = quantity;
        row.automatic = false;
        return row;
    }

    private static StockClosingMovementEntity movement(long id, String kindLabel, String reference, int delta, boolean applied,
                                                       boolean removed) {
        StockClosingMovementEntity row = new StockClosingMovementEntity();
        row.movementId = id; row.productId = 1L; row.productName = "Roos in stolp rood"; row.locationId = 1L;
        row.locationName = "Magazijn"; row.bookedAt = Instant.parse("2027-01-02T08:02:00Z"); row.kindLabel = kindLabel;
        row.refText = reference; row.delta = delta; row.effectiveDelta = delta; row.applied = applied; row.defaultApplied = applied;
        row.removed = removed; row.review = false;
        return row;
    }

    private static StockClosingDecisionEntity decision(long id, String kind) {
        StockClosingDecisionEntity decision = new StockClosingDecisionEntity();
        decision.id = id; decision.kind = kind; decision.decidedBy = "emre"; decision.decidedByName = "Emre Yilmaz";
        decision.decidedAt = Instant.parse("2027-01-10T08:00:00Z");
        return decision;
    }
}
