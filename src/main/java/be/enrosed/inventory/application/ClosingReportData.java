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
import be.enrosed.inventory.application.StockClosingDecisionService.Index;
import be.enrosed.inventory.domain.ClosingDecisionKind;
import be.enrosed.inventory.domain.CountReason;
import be.enrosed.inventory.domain.PayeeLabels;
import be.enrosed.inventory.domain.WriteDownReason;
import be.enrosed.shared.company.CompanyProfile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the two files for the accountant print, assembled from the
 * stored rows of one closing and, for a correction, from the comparison with
 * the stored rows of the version it replaces.
 *
 * Nothing here reads live data. The one value that is not a stored row is
 * the company head of a concept file, which the caller hands in; "Definitief
 * maken" copies it onto the closing. The PDF and the workbook take their
 * tables from the same methods, so a section and its sheet cannot drift
 * apart in columns or figures.
 */
public record ClosingReportData(StockClosingEntity closing, CompanyIdentity company, List<Notice> notices,
                                List<StockClosingArticleEntity> articles, List<StockClosingLayerEntity> layers,
                                List<StockClosingLineEntity> lines, List<StockClosingContainerEntity> containers,
                                List<StockClosingLotEntity> lots, List<StockClosingSeparateEntity> separates,
                                List<StockClosingWriteDownEntity> writeDowns, List<StockClosingMovementEntity> movements,
                                List<StockClosingDecisionEntity> decisions, Map<Long, Integer> closingYears,
                                ClosingVersionDiff.Changes changes) {

    public static final String NOT_IN_VALUE_OTHER = "Bank- en betalingskosten en andere bedragen die niet bij de zending horen"
            + " ('Bijkomende kosten' in het ERP)";
    public static final String OTHER_STREAM = "Bank- en betalingskosten e.a. onder 'Bijkomende kosten'";
    public static final String OLDER_HEADING = "Oudere facturen zonder afpunten, niet verwerkt";
    public static final String LOCATION_NOTE = "De verdeling per locatie is een verhouding naar aantal en dient ter info;"
            + " het producttotaal is het gewaardeerde cijfer. Het aantal per locatie bevat ook goederen van derden,"
            + " partnerstuks en gefactureerde stuks; de waarde betreft alleen de eigen voorraad.";
    public static final String TRANSIT_NOTE = "De waarde van goederen onderweg is per product het bestelde aantal x de"
            + " waarde per stuk (4 decimalen); een verschil van enkele centen met het bedrag van de container onder"
            + " 'Geschatte kosten' en 'Containers' is afronding.";
    public static final String LOT_NOTE = "Aandeel van een partij = bedrag van de container x sleutel van de partij"
            + " / som van de sleutels van de container.";
    public static final String RATES_STATEMENT = "De koersen zijn op de container ingevoerd; het ERP bewaart geen"
            + " factuurdatum of koersbron.";

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final List<String> STREAMS = List.of("SUPPLIER", "LOGISTICS", "SEPARATE");

    /** The company as the head of the files names it. */
    public record CompanyIdentity(String name, String vat, String address) {}

    /** How a column prints its values; the cells themselves are typed (text, whole number, decimal, day, moment, yes or no). */
    public enum Kind { TEXT, COUNT, MONEY, UNIT, RATE, DAY, MOMENT, YES_NO, ANY }

    public record Column(String title, Kind kind) {}

    /** One section of the PDF and its sheet: the same columns, the same rows, an optional row of totals. */
    public record Table(String title, List<Column> columns, List<List<Object>> rows, List<Object> totals) {}

    /** One line of the summary: an amount, or a number of pieces for the goods that carry no value. */
    public record SummaryLine(String label, BigDecimal amountEur, Integer quantity, boolean total) {}

    /** One payee stream of a valued container with the accrual decision that was entered on it. */
    public record Stream(StockClosingContainerEntity container, String payee, String status, BigDecimal plannedEur,
                         BigDecimal paidEur, BigDecimal openEur, BigDecimal includedEur, BigDecimal estimatedEur,
                         StockClosingDecisionEntity accrual, boolean stale) {}

    /**
     * The name, the VAT number and the address of the company as a closing keeps them: the legal
     * name, or the trade name when that is blank; the non-blank address parts joined with ", ".
     */
    public static CompanyIdentity companyIdentity(CompanyProfile profile) {
        if (profile == null) return new CompanyIdentity(null, null, null);
        String name = blank(profile.legalName()) ? profile.name() : profile.legalName();
        String town = ((profile.postalCode() == null ? "" : profile.postalCode().strip()) + " "
                + (profile.city() == null ? "" : profile.city().strip())).strip();
        List<String> parts = new ArrayList<>();
        for (String part : Arrays.asList(profile.addressLine(), town, profile.countryCode())) {
            if (!blank(part)) parts.add(part.strip());
        }
        return new CompanyIdentity(cut(name == null ? null : name.strip(), 255),
                cut(profile.vatNumber() == null ? null : profile.vatNumber().strip(), 64), cut(String.join(", ", parts), 500));
    }

    /**
     * The report of a closing from its stored view. A final closing prints the company it was
     * frozen with; a concept prints the profile of today.
     */
    public static ClosingReportData of(StockClosingService.View view, CompanyProfile liveProfile) {
        StockClosingEntity closing = view.closing();
        CompanyIdentity company = StockClosingService.STATUS_FINAL.equals(closing.status)
                ? new CompanyIdentity(closing.companyName, closing.companyVat, closing.companyAddress)
                : companyIdentity(liveProfile);
        return new ClosingReportData(closing, company, view.notices(), view.articles(), view.layers(), view.lines(),
                view.containers(), view.lots(), view.separates(), view.writeDowns(), view.movements(), view.decisions(),
                view.closingYears(), view.versionChanges());
    }

    public boolean concept() {
        return !StockClosingService.STATUS_FINAL.equals(closing.status);
    }

    public boolean correction() {
        return closing.supersedesId != null;
    }

    public String statusLabel() {
        return concept() ? "CONCEPT, niet definitief" : "Definitief";
    }

    /** "jaarinventaris-2026-v1.pdf"; a concept says so in its name. */
    public String filename(String extension) {
        return "jaarinventaris-" + closing.closingYear + "-v" + closing.versionNo + (concept() ? "-concept" : "") + "." + extension;
    }

    /* --------------------------------------------------------------- summary */

    public List<SummaryLine> summary() {
        int thirdParty = 0;
        for (StockClosingSeparateEntity row : kind(FifoValuer.KIND_THIRD_PARTY)) thirdParty += count(row.quantity);
        List<SummaryLine> lines = new ArrayList<>();
        lines.add(new SummaryLine("Aanschafwaarde eigen voorraad", nz(closing.costValueEur), null, false));
        lines.add(new SummaryLine("Waardeverminderingen", nz(closing.writeDownEur), null, false));
        lines.add(new SummaryLine("Eigen voorraad na waardevermindering", nz(closing.ownValueEur), null, false));
        lines.add(new SummaryLine("waarvan demostukken", nz(closing.demoValueEur), null, false));
        lines.add(new SummaryLine("Partnercontainers opgenomen", nz(closing.partnerIncludedEur), null, false));
        lines.add(new SummaryLine("Partnercontainers niet opgenomen", nz(closing.partnerExcludedEur), null, false));
        lines.add(new SummaryLine("Goederen onderweg opgenomen", nz(closing.transitIncludedEur), null, false));
        lines.add(new SummaryLine("Goederen onderweg niet opgenomen", nz(closing.transitExcludedEur), null, false));
        lines.add(new SummaryLine("Gefactureerd, uit eigen voorraad gehaald", nz(closing.invoicedOutEur), null, false));
        lines.add(new SummaryLine("Goederen van derden (aantal, zonder waarde)", null, thirdParty, false));
        lines.add(new SummaryLine("Totaal voorraadwaarde volgens de genomen beslissingen", nz(closing.totalValueEur), null, true));
        lines.add(new SummaryLine("waarvan op geschatte kosten", nz(closing.estimatedEur), null, false));
        return lines;
    }

    /** The factual line under the total when partner containers or goods in transit are in it; null otherwise. */
    public String marketNote() {
        BigDecimal atCost = nz(closing.partnerIncludedEur).add(nz(closing.transitIncludedEur));
        if (atCost.signum() <= 0) return null;
        return "Op opgenomen partnercontainers en goederen onderweg (€ " + ClosingNotices.euro(atCost)
                + ") is geen lagere marktwaarde ingevoerd; het ERP voorziet daar geen waardevermindering.";
    }

    /* ------------------------------------------------------------ own stock */

    /** The products of the valued list: everything that holds own pieces, a value or pieces without one. */
    public List<StockClosingArticleEntity> valuedArticles() {
        return articles.stream().filter(article -> count(article.ownQuantity) != 0 || count(article.unvaluedQuantity) != 0
                        || nz(article.costValueEur).signum() != 0)
                .sorted(Comparator.comparing((StockClosingArticleEntity article) -> text(article.categoryName), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(article -> text(article.productName), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(article -> article.productId)).toList();
    }

    public List<StockClosingLayerEntity> ownLayers(long productId) {
        return layers.stream().filter(layer -> Objects.equals(layer.productId, productId) && FifoValuer.BLOCK_OWN.equals(layer.block))
                .sorted(Comparator.comparing((StockClosingLayerEntity layer) -> count(layer.position))).toList();
    }

    public String layerLabel(StockClosingLayerEntity layer) {
        return FifoValuer.layerLabel(layer, closingYears);
    }

    public Table perLocation() {
        List<Column> columns = List.of(col("Boekjaar", Kind.COUNT), col("Afsluitdatum", Kind.DAY), col("Locatie"), col("SKU"),
                col("Product"), col("Categorie"), col("Eenheid"), col("Stuks per display", Kind.COUNT), col("Soort"),
                col("Geteld", Kind.COUNT), col("Geteld door"), col("Geteld op", Kind.MOMENT),
                col("Correctie naar afsluitdatum", Kind.COUNT), col("Aantal op afsluitdatum (alle stuks)", Kind.COUNT),
                col("Waarvan eigen voorraad", Kind.COUNT), col("Goederen (leverancier)", Kind.MONEY),
                col("Transport via leverancier (CIF)", Kind.MONEY), col(PayeeLabels.LOGISTICS, Kind.MONEY),
                col(PayeeLabels.SEPARATE, Kind.MONEY), col("Beginwaarde", Kind.MONEY), col("Aanschafwaarde", Kind.MONEY),
                col("Waarvan geschat", Kind.MONEY), col("Waardevermindering", Kind.MONEY), col("Waarde", Kind.MONEY));
        Map<Long, StockClosingArticleEntity> byProduct = articlesById();
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingLineEntity line : lines.stream().sorted(Comparator
                .comparing((StockClosingLineEntity row) -> text(row.locationName), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> text(row.productName), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.productId)).toList()) {
            StockClosingArticleEntity article = byProduct.get(line.productId);
            rows.add(row(closing.closingYear, closing.closingDate, line.locationName, line.sku, line.productName,
                    article == null ? null : article.categoryName, article == null ? null : article.unitKey,
                    article == null ? null : article.piecesPerUnit, article != null && Boolean.TRUE.equals(article.demo) ? "Demo" : "Eigen",
                    line.countedQuantity, line.countedByName, line.countedAt, count(line.rollDelta), count(line.closingQuantity),
                    ownQuantity(line), nz(line.goodsEur), nz(line.transportEur), nz(line.logisticsEur), nz(line.separateEur),
                    nz(line.openingEur), nz(line.costValueEur), nz(line.estimatedEur), nz(line.writeDownEur), nz(line.ownValueEur)));
        }
        return new Table("Voorraad per locatie", columns, rows, totals(columns, rows, 2, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23));
    }

    /** The own pieces of a product at the location of this row: derived from the article and its line rows, never stored. */
    public int ownQuantity(StockClosingLineEntity line) {
        StockClosingArticleEntity article = articlesById().get(line.productId);
        if (article == null) return 0;
        Map<Long, Integer> held = new LinkedHashMap<>();
        for (StockClosingLineEntity other : lines) {
            if (Objects.equals(other.productId, line.productId)) held.put(other.locationId, count(other.closingQuantity));
        }
        return FifoValuer.ownQuantityPerLocation(count(article.ownQuantity), held).getOrDefault(line.locationId, 0);
    }

    public Table products() {
        List<Column> columns = List.of(col("SKU"), col("Product"), col("Categorie"), col("Eenheid"), col("Soort"),
                col("Aantal totaal", Kind.COUNT), col("Goederen van derden", Kind.COUNT), col("Partner", Kind.COUNT),
                col("Gefactureerd uit", Kind.COUNT), col("Eigen aantal", Kind.COUNT), col("Zonder waarde", Kind.COUNT),
                col("Waarde per stuk (gem.)", Kind.UNIT), col("Goederen (leverancier)", Kind.MONEY),
                col("Transport via leverancier (CIF)", Kind.MONEY), col(PayeeLabels.LOGISTICS, Kind.MONEY),
                col(PayeeLabels.SEPARATE, Kind.MONEY), col("Beginwaarde", Kind.MONEY), col("Aanschafwaarde", Kind.MONEY),
                col("Waardevermindering", Kind.MONEY), col("Waarde", Kind.MONEY));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingArticleEntity article : articles) {
            rows.add(row(article.sku, article.productName, article.categoryName, article.unitKey,
                    Boolean.TRUE.equals(article.demo) ? "Demo" : "Eigen", count(article.closingQuantity),
                    count(article.thirdPartyQuantity), count(article.partnerQuantity), count(article.invoicedOutQuantity),
                    count(article.ownQuantity), count(article.unvaluedQuantity), article.averageUnitEur, nz(article.goodsEur),
                    nz(article.transportEur), nz(article.logisticsEur), nz(article.separateEur), nz(article.openingEur),
                    nz(article.costValueEur), nz(article.writeDownEur), nz(article.ownValueEur)));
        }
        return new Table("Producten", columns, rows, totals(columns, rows, 1, 5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18, 19));
    }

    public Table fifoLayers() {
        List<Column> columns = List.of(col("SKU"), col("Product"), col("Blok"), col("Volgorde", Kind.COUNT), col("Bron"),
                col("Container"), col("Ontvangen op", Kind.DAY), col("Bron beginwaarde"), col("Aantal in partij", Kind.COUNT),
                col("Aantal gebruikt", Kind.COUNT), col("Waarde per stuk", Kind.UNIT), col("Goederen per stuk", Kind.UNIT),
                col("Transport per stuk", Kind.UNIT), col("Douane & transport per stuk", Kind.UNIT),
                col("Inspectie per stuk", Kind.UNIT), col("Waarde", Kind.MONEY), col("Waarvan geschat", Kind.MONEY),
                col("Aantal met waardevermindering", Kind.COUNT), col("Waardevermindering", Kind.MONEY));
        Map<Long, StockClosingArticleEntity> byProduct = articlesById();
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingLayerEntity layer : layers) {
            StockClosingArticleEntity article = byProduct.get(layer.productId);
            rows.add(row(article == null ? null : article.sku, article == null ? null : article.productName,
                    FifoValuer.BLOCK_INVOICED.equals(layer.block) ? "Gefactureerd, uit eigen voorraad" : "Eigen voorraad",
                    layer.position, sourceLabel(layer.source), layer.displayName != null ? layer.displayName : layer.orderNumber,
                    layer.receivedOn, layer.openingSource, layer.capacity, count(layer.quantity), layer.unitValueEur,
                    layer.unitGoodsEur, layer.unitTransportEur, layer.unitLogisticsEur, layer.unitSeparateEur, nz(layer.valueEur),
                    nz(layer.estimatedEur), count(layer.writeDownQuantity), nz(layer.writeDownEur)));
        }
        return new Table("Partijen (FIFO)", columns, rows, null);
    }

    private static String sourceLabel(String source) {
        return switch (source == null ? "" : source) {
            case FifoValuer.SOURCE_PREVIOUS -> "Vorige inventaris";
            case FifoValuer.SOURCE_OPENING -> "Beginwaarde";
            default -> "Partij";
        };
    }

    public Table writeDownTable() {
        List<Column> columns = List.of(col("Product"), col("Partij"), col("Aantal", Kind.COUNT),
                col("Aanschafwaarde per stuk", Kind.UNIT), col("Marktwaarde per stuk", Kind.UNIT),
                col("Waardevermindering", Kind.MONEY), col("Reden"), col("Toelichting"), col("Door"), col("Op", Kind.MOMENT));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingWriteDownEntity down : writeDowns) {
            rows.add(row(down.productName, down.layerLabel, count(down.quantity), down.layerUnitEur, down.marketUnitEur,
                    nz(down.amountEur), WriteDownReason.labelOf(down.reasonCode), down.reason, down.decidedByName, down.decidedAt));
        }
        return new Table("Waardeverminderingen", columns, rows, rows.isEmpty() ? null : totals(columns, rows, 0, 5));
    }

    /* ------------------------------------------------------------ containers */

    /** The containers this closing values itself; the ones recomputed for comparison with an earlier year are left out. */
    public List<StockClosingContainerEntity> valuedContainers() {
        return containers.stream().filter(container -> !StockClosingService.ROLE_PREVIOUS.equals(container.role)).toList();
    }

    public List<StockClosingLotEntity> lotsOf(StockClosingContainerEntity container) {
        return lots.stream().filter(lot -> Objects.equals(lot.containerId, container.id)).toList();
    }

    public List<Stream> streams(StockClosingContainerEntity container) {
        Index index = Index.of(decisions);
        List<Stream> streams = new ArrayList<>();
        for (String payee : STREAMS) {
            String status;
            BigDecimal planned, paid, open, included, estimated;
            switch (payee) {
                case "SUPPLIER" -> {
                    status = container.supplierStatus; planned = container.supplierPlannedEur; paid = container.supplierPaidEur;
                    open = container.supplierOpenEur; included = container.supplierIncludedEur; estimated = container.supplierEstimatedEur;
                }
                case "LOGISTICS" -> {
                    status = container.logisticsStatus; planned = container.logisticsPlannedEur; paid = container.logisticsPaidEur;
                    open = container.logisticsOpenEur; included = container.logisticsIncludedEur; estimated = container.logisticsEstimatedEur;
                }
                default -> {
                    status = container.separateStatus; planned = container.separatePlannedEur; paid = container.separatePaidEur;
                    open = container.separateOpenEur; included = container.separateIncludedEur; estimated = container.separateEstimatedEur;
                }
            }
            StockClosingDecisionEntity accrual = StockClosingService.ROLE_PREVIOUS.equals(container.role) ? null
                    : index.accrual(container.purchaseOrderId, payee);
            /* On a container in transit only the supplier's amount is measured against the Afspraak. */
            boolean measured = !StockClosingService.ROLE_TRANSIT.equals(container.role) || "SUPPLIER".equals(payee);
            boolean stale = accrual != null && measured && nz(accrual.basisAmountEur).compareTo(nz(open)) != 0;
            streams.add(new Stream(container, payee, status, nz(planned), nz(paid), nz(open), nz(included), nz(estimated), accrual, stale));
        }
        return streams;
    }

    public Table containerTable() {
        List<Column> columns = new ArrayList<>(List.of(col("Container"), col("Naam"), col("Leverancier"),
                col("Incoterm leverancier (fiche)"), col("Rol"), col("Ontvangen op", Kind.DAY), col("Koers geldt tot", Kind.DAY),
                col("Bron van die datum"), col("Koers CNY/USD", Kind.RATE), col("Koers USD/EUR goederen", Kind.RATE),
                col("Koers USD/EUR transport", Kind.RATE), col("Transport via leverancier (CIF)", Kind.YES_NO),
                col("Verdeelsleutels"), col("Opmerkingen")));
        for (String payee : STREAMS) {
            String label = PayeeLabels.of(payee);
            columns.add(col(label + ": Status"));
            columns.add(col(label + ": Afspraak", Kind.MONEY));
            columns.add(col(label + ": Betaald", Kind.MONEY));
            columns.add(col(label + ": Nog open", Kind.MONEY));
            columns.add(col(label + ": Opgenomen", Kind.MONEY));
            columns.add(col(label + ": Waarvan geschat", Kind.MONEY));
        }
        columns.addAll(List.of(col("Prijscreditnota's (verlagen de waarde)", Kind.MONEY),
                col("Tegoed buiten de voorraadwaarde", Kind.MONEY), col("Koersverschil (buiten waarde)", Kind.MONEY),
                col(OTHER_STREAM + " (buiten waarde)", Kind.MONEY), col("Enrosed kost (buiten waarde)", Kind.MONEY),
                col("Aanschafwaarde", Kind.MONEY), col("Waarvan geschat", Kind.MONEY)));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingContainerEntity container : containers) {
            List<Object> row = new ArrayList<>(Arrays.asList(container.orderNumber, container.displayName, container.supplierName,
                    container.supplierIncoterm, roleLabel(container.role), container.receivedOn, container.rateCutoffDate,
                    StockClosingService.borderLabel(container.rateCutoffSource), container.cnyToUsd, container.usdToEurGoods,
                    container.usdToEurTransport, Boolean.TRUE.equals(container.cif),
                    PayeeLabels.allocationLabel(container.allocOrigin, container.allocFreight, container.allocDestination,
                            container.allocSeparate, container.groupVariants, container.separateInPiecePrice),
                    container.notes));
            for (Stream stream : streams(container)) {
                row.addAll(Arrays.asList(streamStatusLabel(stream.status()), stream.plannedEur(), stream.paidEur(),
                        stream.openEur(), stream.includedEur(), stream.estimatedEur()));
            }
            row.addAll(Arrays.asList(nz(container.priceCreditEur), nz(container.lossCreditEur), nz(container.exchangeDifferenceEur),
                    nz(container.otherExcludedEur), nz(container.enrosedCostExcludedEur), nz(container.acquisitionEur),
                    nz(container.estimatedEur)));
            rows.add(row);
        }
        return new Table("Containers", columns, rows, null);
    }

    public Table lotTable() {
        List<Column> columns = List.of(col("Container"), col("SKU"), col("Product"), col("Besteld", Kind.COUNT),
                col("Ontvangen", Kind.COUNT), col("Beschadigd", Kind.COUNT), col("Later gemeld", Kind.COUNT),
                col("Aangerekend aantal", Kind.COUNT), col("Deler goederen", Kind.COUNT), col("Deler containerkosten", Kind.COUNT),
                col("Bruikbaar in partij", Kind.COUNT), col("Inkoopprijs per stuk (EUR)", Kind.UNIT),
                col("Sleutel goederen", Kind.MONEY), col("Sleutel transport leverancier", Kind.MONEY),
                col("Sleutel douane & transport", Kind.MONEY), col("Sleutel inspectie", Kind.MONEY), col("Goederen", Kind.MONEY),
                col("Prijscreditnota", Kind.MONEY), col("Transport via leverancier", Kind.MONEY),
                col(PayeeLabels.LOGISTICS, Kind.MONEY), col(PayeeLabels.SEPARATE, Kind.MONEY), col("Kost van de partij", Kind.MONEY),
                col("Goederen per stuk", Kind.UNIT), col("Transport per stuk", Kind.UNIT),
                col("Douane & transport per stuk", Kind.UNIT), col("Inspectie per stuk", Kind.UNIT),
                col("Waarde per stuk", Kind.UNIT), col("Geschat per stuk", Kind.UNIT),
                col("Volgens berekening, ter info: Lokale kosten bij vertrek", Kind.MONEY),
                col("Volgens berekening, ter info: Zeevracht", Kind.MONEY),
                col("Volgens berekening, ter info: Invoerrechten", Kind.MONEY),
                col("Volgens berekening, ter info: Kosten na aankomst", Kind.MONEY),
                col("Volgens berekening, ter info: Invoerrecht %", Kind.UNIT),
                col("Waarde per stuk vorige afsluiting", Kind.UNIT), col("Status"));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingContainerEntity container : containers) {
            for (StockClosingLotEntity lot : lotsOf(container)) {
                rows.add(row(container.displayName, lot.sku, lot.productName, count(lot.orderedQuantity), count(lot.receivedQuantity),
                        count(lot.damagedQuantity), count(lot.laterLostQuantity), count(lot.billedQuantity), count(lot.goodsDivisor),
                        count(lot.costDivisor), count(lot.capacity), lot.unitPriceEur, lot.goodsKeyEur, lot.transportKeyEur,
                        lot.logisticsKeyEur, lot.separateKeyEur, nz(lot.goodsEur), nz(lot.priceCreditEur), nz(lot.transportEur),
                        nz(lot.logisticsEur), nz(lot.separateEur), nz(lot.lotCostEur), lot.unitGoodsEur, lot.unitTransportEur,
                        lot.unitLogisticsEur, lot.unitSeparateEur, lot.unitValueEur, lot.unitEstimatedEur, lot.calcOriginEur,
                        lot.calcFreightEur, lot.calcDutyEur, lot.calcDestinationEur, lot.calcDutyRatePct, lot.previousUnitValueEur,
                        lotStatusLabel(lot.status)));
            }
        }
        return new Table("Containerlijnen", columns, rows, null);
    }

    public Table paymentTable() {
        List<Column> columns = List.of(col("Container"), col("Datum", Kind.DAY), col("Betaalstroom"), col("Omschrijving"),
                col("Bedrag", Kind.MONEY), col("Munt"), col("Geboekte eurowaarde", Kind.MONEY),
                col("Getelde eurowaarde", Kind.MONEY), col("In de waarde", Kind.YES_NO), col("Regel"));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingContainerEntity container : valuedContainers()) {
            for (Map<String, Object> payment : StockClosingService.readJson(container.paymentsJson)) {
                boolean other = "OTHER".equals(payment.get("payee"));
                rows.add(row(container.displayName, day(payment.get("paidOn")),
                        other ? OTHER_STREAM : text(payment.get("payeeLabel")), text(payment.get("label")),
                        decimal(payment.get("amount")), text(payment.get("currency")), decimal(payment.get("storedEur")),
                        decimal(payment.get("countedEur")), Boolean.TRUE.equals(payment.get("inValue")),
                        other ? "Niet in de waarde: " + OTHER_STREAM.substring(0, 1).toLowerCase() + OTHER_STREAM.substring(1)
                                : text(payment.get("rule"))));
            }
        }
        return new Table("Betalingen", columns, rows, null);
    }

    public Table creditTable() {
        List<Column> columns = List.of(col("Container"), col("Datum", Kind.DAY), col("Reden"), col("Bedrag", Kind.MONEY),
                col("Munt"), col("Eurowaarde", Kind.MONEY), col("Behandeling"), col("Beslist door"), col("Reden van de beslissing"));
        Map<Long, StockClosingDecisionEntity> byId = new LinkedHashMap<>();
        decisions.forEach(decision -> byId.put(decision.id, decision));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingContainerEntity container : valuedContainers()) {
            for (Map<String, Object> credit : StockClosingService.readJson(container.creditsJson)) {
                StockClosingDecisionEntity decision = credit.get("decisionId") instanceof Number id ? byId.get(id.longValue()) : null;
                rows.add(row(container.displayName, day(credit.get("notedOn")), text(credit.get("reasonLabel")),
                        decimal(credit.get("amount")), text(credit.get("currency")), decimal(credit.get("countedEur")),
                        text(credit.get("treatmentLabel")), decision == null ? null : decision.decidedByName, text(credit.get("reason"))));
            }
        }
        return new Table("Creditnota's en tegoeden", columns, rows, null);
    }

    /** One row per stream that carries an estimated amount or on which an amount owed was entered. */
    public Table estimatedTable() {
        List<Column> columns = List.of(col("Container"), col("Betaalstroom"), col("Afspraak", Kind.MONEY),
                col("Betaald", Kind.MONEY), col("Nog open volgens Afspraak", Kind.MONEY),
                col("Nog verschuldigd (ingevoerd)", Kind.MONEY), col("Opgenomen", Kind.MONEY),
                col("waarvan geschat", Kind.MONEY), col("Basis"));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingContainerEntity container : valuedContainers()) {
            for (Stream stream : streams(container)) {
                if (stream.estimatedEur().signum() == 0 && stream.accrual() == null) continue;
                boolean applied = stream.accrual() != null && !stream.stale();
                rows.add(row(container.displayName, PayeeLabels.of(stream.payee()), stream.plannedEur(), stream.paidEur(),
                        stream.openEur(), applied ? nz(stream.accrual().amountEur) : null, stream.includedEur(),
                        stream.estimatedEur(), applied ? stream.accrual().reason : "Afspraak op de container"));
            }
        }
        return new Table("Geschatte kosten", columns, rows, null);
    }

    public Table notInValueTable() {
        List<Column> columns = List.of(col("Container"), col("Enrosed kost (buiten waarde)", Kind.MONEY),
                col(NOT_IN_VALUE_OTHER, Kind.MONEY), col("Koersverschil", Kind.MONEY),
                col("Tegoed leverancier buiten de voorraadwaarde", Kind.MONEY));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingContainerEntity container : valuedContainers()) {
            rows.add(row(container.displayName, nz(container.enrosedCostExcludedEur), nz(container.otherExcludedEur),
                    nz(container.exchangeDifferenceEur), nz(container.lossCreditEur)));
        }
        return new Table("Niet in de waarde", columns, rows, rows.isEmpty() ? null : totals(columns, rows, 0, 1, 2, 3, 4));
    }

    /* ------------------------------------------------------- separate blocks */

    public List<StockClosingSeparateEntity> kind(String kind) {
        return separates.stream().filter(row -> kind.equals(row.kind)).toList();
    }

    public Table partnerTable() {
        List<Column> columns = List.of(col("Container"), col("Partner"), col("Product"), col("Aantal", Kind.COUNT),
                col("Waarde per stuk", Kind.UNIT), col("Waarde", Kind.MONEY), col("Opgenomen", Kind.YES_NO), col("Reden"));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingSeparateEntity row : kind(FifoValuer.KIND_PARTNER)) {
            rows.add(row(row.documentName != null ? row.documentName : row.documentNumber, row.counterparty, row.productName,
                    count(row.quantity), row.unitValueEur, row.valueEur, row.included, row.reason));
        }
        return new Table("Partnercontainers", columns, rows, null);
    }

    public Table thirdPartyTable() {
        List<Column> columns = List.of(col("Eigenaar"), col("Product"), col("Aantal", Kind.COUNT), col("Reden"));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingSeparateEntity row : kind(FifoValuer.KIND_THIRD_PARTY)) {
            rows.add(row(row.counterparty, row.productName, count(row.quantity), row.reason));
        }
        return new Table("Goederen van derden", columns, rows, null);
    }

    /** One row per container on the water; its products are on the sheet of the container lines. */
    public Table transitTable() {
        List<Column> columns = List.of(col("Container"), col("Leverancier"), col("Incoterm leverancier (fiche)"),
                col("Transport via leverancier", Kind.YES_NO), col("Afvaart", Kind.DAY),
                col("Betaald t/m afsluitdatum", Kind.MONEY), col("Waarde", Kind.MONEY), col("Opgenomen", Kind.YES_NO),
                col("Eigendom of risico vanaf", Kind.DAY), col("Reden"));
        Map<Long, List<StockClosingSeparateEntity>> byContainer = new LinkedHashMap<>();
        for (StockClosingSeparateEntity row : kind(FifoValuer.KIND_TRANSIT)) {
            byContainer.computeIfAbsent(row.purchaseOrderId, key -> new ArrayList<>()).add(row);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (List<StockClosingSeparateEntity> group : byContainer.values()) {
            StockClosingSeparateEntity first = group.getFirst();
            StockClosingContainerEntity container = containers.stream()
                    .filter(candidate -> Objects.equals(candidate.purchaseOrderId, first.purchaseOrderId)
                            && StockClosingService.ROLE_TRANSIT.equals(candidate.role)).findFirst().orElse(null);
            BigDecimal value = ZERO;
            for (StockClosingSeparateEntity row : group) value = value.add(nz(row.valueEur));
            rows.add(row(first.documentName != null ? first.documentName : first.documentNumber,
                    container != null ? container.supplierName : first.counterparty,
                    container == null ? null : container.supplierIncoterm, container == null ? null : Boolean.TRUE.equals(container.cif),
                    first.shippedOn, first.paidUntilClosingEur, value, first.included, first.ownershipDate, first.reason));
        }
        return new Table("Goederen onderweg", columns, rows, null);
    }

    public Table invoicedTable() {
        List<Column> columns = List.of(col("Factuur"), col("Datum", Kind.DAY), col("Klant"), col("Product"),
                col("Gefactureerd", Kind.COUNT), col("Uit voorraad", Kind.COUNT), col("Waarde per stuk", Kind.UNIT),
                col("Waarde", Kind.MONEY), col("Beslissing"), col("Reden"));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingSeparateEntity row : kind(FifoValuer.KIND_INVOICED)) {
            /* The value covers the pieces taken out of the own stock, which can be fewer than were invoiced. */
            boolean out = FifoValuer.CHOICE_OUT.equals(row.choice);
            rows.add(row(row.documentNumber, row.documentDate, row.counterparty, row.productName, count(row.quantity),
                    out ? row.carvedQuantity : null, out ? row.unitValueEur : null, out ? row.valueEur : null, invoicedLabel(row.choice), row.reason));
        }
        return new Table("Gefactureerd, nog niet afgepunt", columns, rows, null);
    }

    /** The invoices that were never afgepunt and are only listed. */
    public Table olderInvoiceTable() {
        List<Column> columns = List.of(col("Factuur"), col("Datum", Kind.DAY), col("Klant"), col("Aantal", Kind.COUNT));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingSeparateEntity row : kind(FifoValuer.KIND_OLDER)) {
            rows.add(row(row.documentNumber, row.documentDate, row.counterparty, count(row.quantity)));
        }
        return new Table(OLDER_HEADING, columns, rows, null);
    }

    /** "Oudere facturen zonder afpunten: 2, niet verwerkt."; null when the closing lists none. */
    public String olderInvoiceLine() {
        int older = kind(FifoValuer.KIND_OLDER).size();
        return older == 0 ? null : "Oudere facturen zonder afpunten: " + older + ", niet verwerkt.";
    }

    private static String invoicedLabel(String choice) {
        return switch (choice == null ? "" : choice) {
            case FifoValuer.CHOICE_OUT -> "Uit eigen voorraad";
            case FifoValuer.CHOICE_STAYS -> "Blijft eigen voorraad";
            case FifoValuer.CHOICE_GONE -> "Stuks waren al weg";
            default -> "Nog niet beslist";
        };
    }

    /* ------------------------------------------------------ count and roll */

    public Table countDifferenceTable() {
        List<Column> columns = List.of(col("Locatie"), col("Product"), col("Volgens systeem", Kind.COUNT),
                col("Geteld", Kind.COUNT), col("Verschil", Kind.COUNT), col("Reden"), col("Toelichting"), col("Geteld door"),
                col("Tijdstip", Kind.MOMENT));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingLineEntity line : lines) {
            if (line.countDifference == null || line.countDifference == 0) continue;
            rows.add(row(line.locationName, line.productName, line.expectedQuantity, line.countedQuantity, line.countDifference,
                    CountReason.labelOf(line.countReasonCode), line.countReasonNote, line.countedByName, line.countedAt));
        }
        return new Table("Telverschillen", columns, rows, null);
    }

    public Table movementTable() {
        List<Column> columns = List.of(col("Locatie"), col("Product"), col("Geboekt op", Kind.MOMENT), col("Soort"),
                col("Referentie"), col("Aantal", Kind.COUNT), col("Meegerekend"), col("Reden"));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingMovementEntity movement : movements) {
            boolean removed = Boolean.TRUE.equals(movement.removed);
            rows.add(row(movement.locationName, movement.productName, movement.bookedAt, movement.kindLabel, movement.refText,
                    count(movement.effectiveDelta),
                    removed ? "Verwijderd uit de voorraadgeschiedenis" : Boolean.TRUE.equals(movement.applied) ? "ja" : "nee",
                    movement.appliedReason));
        }
        return new Table("Bewegingen rond de afsluitdatum", columns, rows, null);
    }

    /* ------------------------------------------------------------ correction */

    /** "Wijzigingen tegenover versie 1"; null when this closing replaces no version. */
    public String changesTitle() {
        return changes == null ? null : "Wijzigingen tegenover versie " + changes.againstVersionNo();
    }

    /** Every figure that differs from the replaced version, one row each; the first row is the total. */
    public Table changeTable() {
        List<Column> columns = List.of(col("Soort"), col("Onderwerp"), col("Gegeven"), col("Oud", Kind.ANY),
                col("Nieuw", Kind.ANY), col("Verschil", Kind.ANY));
        List<List<Object>> rows = new ArrayList<>();
        if (changes == null) return new Table("Wijzigingen", columns, rows, null);
        rows.add(change("Totaal", "Totaal voorraadwaarde", "Waarde", nz(changes.totalBeforeEur()), nz(changes.totalAfterEur())));
        for (ClosingVersionDiff.ArticleChange article : changes.articles()) {
            if (!Objects.equals(article.quantityBefore(), article.quantityAfter())) {
                rows.add(change("Product", article.productName(), "Aantal", article.quantityBefore(), article.quantityAfter()));
            }
            if (differs(article.costValueBeforeEur(), article.costValueAfterEur())) {
                rows.add(change("Product", article.productName(), "Aanschafwaarde", article.costValueBeforeEur(), article.costValueAfterEur()));
            }
            if (differs(article.writeDownBeforeEur(), article.writeDownAfterEur())) {
                rows.add(change("Product", article.productName(), "Waardevermindering", article.writeDownBeforeEur(), article.writeDownAfterEur()));
            }
        }
        for (ClosingVersionDiff.LotChange lot : changes.lots()) {
            rows.add(change("Partij", lot.displayName() + " · " + lot.productName(), "Waarde per stuk",
                    lot.unitValueBeforeEur(), lot.unitValueAfterEur()));
        }
        for (ClosingVersionDiff.MovementChange movement : changes.movements()) {
            rows.add(row("Beweging", movement.productName() + " · " + movement.locationName()
                            + (movement.reference() == null ? "" : " · " + movement.reference()), "Effect",
                    movement.effectBefore() == null ? "niet in de lijst" : movement.effectBefore(),
                    movement.effectAfter() == null ? "niet in de lijst" : movement.effectAfter(),
                    movement.effectBefore() == null || movement.effectAfter() == null ? null
                            : movement.effectAfter() - movement.effectBefore()));
        }
        for (ClosingVersionDiff.OpeningChange opening : changes.openingLayers()) {
            String subject = opening.productName() + " · " + opening.source();
            if (!Objects.equals(opening.quantityBefore(), opening.quantityAfter())) {
                rows.add(change("Beginwaarde", subject, "Aantal", opening.quantityBefore(), opening.quantityAfter()));
            }
            if (differs(opening.unitValueBeforeEur(), opening.unitValueAfterEur())) {
                rows.add(change("Beginwaarde", subject, "Waarde per stuk", opening.unitValueBeforeEur(), opening.unitValueAfterEur()));
            }
        }
        return new Table("Wijzigingen", columns, rows, null);
    }

    private static List<Object> change(String kind, String subject, String figure, Object before, Object after) {
        Object difference = null;
        if (before instanceof BigDecimal old && after instanceof BigDecimal now) difference = now.subtract(old);
        if (before instanceof Integer old && after instanceof Integer now) difference = now - old;
        return row(kind, subject, figure, before, after, difference);
    }

    private static boolean differs(BigDecimal left, BigDecimal right) {
        return left == null ? right != null : right == null || left.compareTo(right) != 0;
    }

    /* ------------------------------------------------- decisions and notices */

    public Table decisionTable() {
        List<Column> columns = List.of(col("Soort"), col("Onderwerp"), col("Keuze"), col("Aantal", Kind.COUNT),
                col("Bedrag", Kind.ANY), col("Datum", Kind.DAY), col("Reden"), col("Door"), col("Op", Kind.MOMENT));
        List<List<Object>> rows = new ArrayList<>();
        for (StockClosingDecisionEntity decision : decisions) {
            String reason = decision.reasonCode == null ? decision.reason
                    : WriteDownReason.labelOf(decision.reasonCode) + (blank(decision.reason) ? "" : " · " + decision.reason);
            rows.add(row(ClosingDecisionKind.labelOf(decision.kind), subject(decision), choiceLabel(decision), decision.quantity,
                    decision.amountEur != null ? decision.amountEur : decision.unitValueEur, decision.decisionDate, reason,
                    decision.decidedByName, decision.decidedAt));
        }
        return new Table("Beslissingen", columns, rows, null);
    }

    /** What a decision is about, in the words of the stored rows of the closing. */
    public String subject(StockClosingDecisionEntity decision) {
        List<String> parts = new ArrayList<>();
        if (decision.purchaseOrderId != null) {
            parts.add(containers.stream().filter(row -> Objects.equals(row.purchaseOrderId, decision.purchaseOrderId))
                    .map(row -> "Container " + row.displayName).findFirst().orElse("Container #" + decision.purchaseOrderId));
        }
        if (decision.payee != null) parts.add(PayeeLabels.of(decision.payee));
        if (decision.salesOrderId != null) {
            parts.add(separates.stream().filter(row -> Objects.equals(row.salesOrderId, decision.salesOrderId)
                            && row.documentNumber != null).map(row -> "Factuur " + row.documentNumber).findFirst()
                    .orElse("Factuur #" + decision.salesOrderId));
        }
        if (decision.productId != null) {
            parts.add(articles.stream().filter(row -> Objects.equals(row.productId, decision.productId))
                    .map(row -> row.productName).findFirst()
                    .orElseGet(() -> lots.stream().filter(row -> Objects.equals(row.productId, decision.productId))
                            .map(row -> row.productName).findFirst().orElse("Product #" + decision.productId)));
        }
        if (decision.movementId != null) {
            parts.add(movements.stream().filter(row -> Objects.equals(row.movementId, decision.movementId))
                    .map(row -> row.productName + " · " + (row.refText == null ? row.kindLabel : row.refText)).findFirst()
                    .orElse("Beweging #" + decision.movementId));
        }
        if (decision.creditId != null) parts.add("tegoed #" + decision.creditId);
        if (decision.counterparty != null) parts.add("eigenaar " + decision.counterparty);
        return parts.isEmpty() ? ClosingDecisionKind.labelOf(decision.kind) : String.join(" · ", parts);
    }

    private static String choiceLabel(StockClosingDecisionEntity decision) {
        ClosingDecisionKind kind = ClosingDecisionKind.of(decision.kind);
        if (kind == null) return decision.choice;
        boolean yes = Boolean.TRUE.equals(decision.flag);
        return switch (kind) {
            case ACCRUAL -> yes ? "Factuur ontvangen" : "Nog geen factuur";
            case SUPPLIER_BILLED -> "GELEVERD".equals(decision.choice) ? "Geleverde stuks aangerekend" : "Bestelde stuks aangerekend";
            case CREDIT_TREATMENT -> StockClosingService.treatmentLabel(decision.choice);
            case TRANSIT, PARTNER_CONTAINER -> yes ? "Opgenomen" : "Niet opgenomen";
            case INVOICED -> invoicedLabel(decision.choice);
            case MOVEMENT -> yes ? "Meegerekend" : "Niet meegerekend";
            case VAT_CONFIRMATION -> yes ? "Bevestigd" : "Niet bevestigd";
            case WRITE_DOWN -> decision.quantity == null ? "Alle overblijvende stuks" : null;
            default -> null;
        };
    }

    public List<Notice> warnings() {
        return notices.stream().filter(notice -> !notice.blocker()).toList();
    }

    public List<Notice> blockers() {
        return notices.stream().filter(Notice::blocker).toList();
    }

    public Table noticeTable() {
        List<Column> columns = List.of(col("Soort"), col("Tekst"));
        List<List<Object>> rows = new ArrayList<>();
        for (Notice notice : notices) {
            rows.add(row(notice.blocker() ? "Blokkeert" : "Aandacht", ClosingNotices.reportText(notice.message())));
        }
        return new Table("Aandachtspunten", columns, rows, null);
    }

    /* ------------------------------------------------------------ statements */

    /**
     * What the user declared, never a fact of the ERP: the VAT confirmation with name and day, the
     * line about the rates, and every container whose day of purchase does not come from its receipt.
     */
    public List<String> statements() {
        List<String> statements = new ArrayList<>();
        for (StockClosingDecisionEntity decision : decisions) {
            if (!ClosingDecisionKind.VAT_CONFIRMATION.name().equals(decision.kind) || !Boolean.TRUE.equals(decision.flag)) continue;
            statements.add(text(decision.decidedByName) + " bevestigde op "
                    + (decision.decidedAt == null ? "-" : DAY.format(decision.decidedAt.atZone(InventoryClock.BRUSSELS)))
                    + ": de betalingen onder " + PayeeLabels.SUPPLIER + ", " + PayeeLabels.LOGISTICS + " en " + PayeeLabels.SEPARATE
                    + " zijn zonder aftrekbare btw ingevoerd (bedragen exclusief btw).");
        }
        statements.add(RATES_STATEMENT);
        for (StockClosingContainerEntity container : valuedContainers()) {
            if (container.rateCutoffDate == null || StockClosingService.BORDER_RECEIPT.equals(container.rateCutoffSource)) continue;
            statements.add("Container " + container.displayName + ": eigendom of risico vanaf " + DAY.format(container.rateCutoffDate)
                    + ", " + borderStatement(container.rateCutoffSource) + ".");
        }
        return statements;
    }

    /** Where the day of purchase of a container comes from, as a clause of a sentence. */
    private static String borderStatement(String source) {
        return switch (source == null ? "" : source) {
            case StockClosingService.BORDER_DECISION -> "door de gebruiker ingevoerd";
            case StockClosingService.BORDER_TRANSIT -> "opgegeven bij de beslissing over goederen onderweg";
            case StockClosingService.BORDER_PREVIOUS -> "overgenomen uit de vorige afsluiting";
            case StockClosingService.BORDER_CLOSING_DATE -> "de afsluitdatum, omdat de container niet is opgenomen";
            default -> "de ontvangstdatum";
        };
    }

    /* --------------------------------------------------------------- helpers */

    private Map<Long, StockClosingArticleEntity> articlesById() {
        Map<Long, StockClosingArticleEntity> byProduct = new LinkedHashMap<>();
        articles.forEach(article -> byProduct.put(article.productId, article));
        return byProduct;
    }

    private static String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case StockClosingService.ROLE_PARTNER -> "Partnercontainer";
            case StockClosingService.ROLE_TRANSIT -> "Onderweg";
            case StockClosingService.ROLE_PREVIOUS -> "Vorige afsluiting (ter vergelijking)";
            default -> "Eigen";
        };
    }

    /** The status of a payee stream in the words of the Nacalculatie. */
    private static String streamStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "PLANNED" -> "Begroot";
            case "UNPAID" -> "Nog niet betaald";
            case "PARTIAL" -> "Gedeeltelijk betaald";
            case "PAID" -> "Volledig betaald";
            case "OVERPAID" -> "Meer betaald";
            case "SETTLED_LOWER" -> "Lager afgerekend";
            case "NOT_APPLICABLE" -> "Niet van toepassing";
            case "ADDITIONAL" -> "Extra betaling";
            default -> status;
        };
    }

    private static String lotStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "OK" -> "In orde";
            case "GEEN_PRIJS" -> "Geen inkoopprijs";
            case "MEER_ONTVANGEN" -> "Meer ontvangen dan besteld";
            case "TEKORT" -> "Minder ontvangen dan besteld";
            case "GEEN_ONTVANGST" -> "Niets ontvangen";
            default -> status;
        };
    }

    private static Column col(String title) {
        return new Column(title, Kind.TEXT);
    }

    private static Column col(String title, Kind kind) {
        return new Column(title, kind);
    }

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    /** A row of totals: "Totaal" under the label column and the sum under each column that is named. */
    private static List<Object> totals(List<Column> columns, List<List<Object>> rows, int labelColumn, int... summed) {
        Object[] totals = new Object[columns.size()];
        totals[labelColumn] = "Totaal";
        for (int column : summed) {
            boolean whole = columns.get(column).kind() == Kind.COUNT;
            int pieces = 0;
            BigDecimal amount = ZERO;
            for (List<Object> row : rows) {
                Object cell = row.get(column);
                if (cell instanceof Integer number) pieces += number;
                if (cell instanceof BigDecimal number) amount = amount.add(number);
            }
            totals[column] = whole ? (Object) pieces : amount;
        }
        return Arrays.asList(totals);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal number ? number : value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }

    private static LocalDate day(Object value) {
        return value == null || value.toString().isBlank() ? null : LocalDate.parse(value.toString());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    /** A moment as the files print it, in Belgian time. */
    public static String moment(Instant at) {
        return at == null ? "" : DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(at.atZone(InventoryClock.BRUSSELS));
    }
}
