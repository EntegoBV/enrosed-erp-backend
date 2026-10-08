package be.enrosed.inventory.adapter.in.rest;

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
import be.enrosed.inventory.adapter.out.persistence.StockOpeningLayerEntity;
import be.enrosed.inventory.adapter.out.persistence.StockValuationRuleEntity;
import be.enrosed.inventory.application.ClosingNotices;
import be.enrosed.inventory.application.ClosingVersionDiff;
import be.enrosed.inventory.application.FifoValuer;
import be.enrosed.inventory.application.StockClosingDecisionService;
import be.enrosed.inventory.application.StockClosingDecisionService.Index;
import be.enrosed.inventory.application.StockClosingService;
import be.enrosed.inventory.application.StockOpeningLayerService;
import be.enrosed.inventory.domain.ClosingDecisionKind;
import be.enrosed.inventory.domain.CountReason;
import be.enrosed.inventory.domain.PayeeLabels;
import be.enrosed.inventory.domain.WriteDownReason;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The JSON of the closings, the opening values and the valuation rule, field for field what the screens read and send. */
public final class StockClosingDtos {

    private StockClosingDtos() {}

    /* ------------------------------------------------------------ requests */

    public record CreateRequest(Integer closingYear, LocalDate closingDate) {}

    public record DateRequest(LocalDate closingDate) {}

    public record DecisionWrite(Long id, String kind, Long purchaseOrderId, Long salesOrderId, List<Long> salesOrderIds,
                                Long productId, Long movementId, Long creditId, String payee, String choice, Boolean flag,
                                Integer quantity, BigDecimal unitValueEur, BigDecimal amountEur, LocalDate decisionDate,
                                String reasonCode, String reason, String counterparty) {
        StockClosingDecisionService.Write toWrite() {
            return new StockClosingDecisionService.Write(id, kind, purchaseOrderId, salesOrderId, salesOrderIds, productId,
                    movementId, creditId, payee, choice, flag, quantity, unitValueEur, amountEur, decisionDate, reasonCode,
                    reason, counterparty);
        }
    }

    public record OpeningLayerWrite(LocalDate asOfDate, String source, List<Row> rows) {
        public record Row(Long productId, Integer quantity, BigDecimal unitValueEur, String note) {}

        StockOpeningLayerService.Write toWrite() {
            return new StockOpeningLayerService.Write(asOfDate, source, rows == null ? null : rows.stream()
                    .map(row -> row == null ? null : new StockOpeningLayerService.Row(row.productId(), row.quantity(),
                            row.unitValueEur(), row.note())).toList());
        }
    }

    /* -------------------------------------------------- rule and opening */

    public record OpeningLayer(long id, long productId, String sku, String productName, int quantity, BigDecimal unitValueEur,
                               LocalDate asOfDate, String source, String note, String createdByName, Instant createdAt) {
        static OpeningLayer from(StockOpeningLayerEntity layer) {
            return new OpeningLayer(layer.id, layer.productId, layer.sku, layer.productName, layer.quantity, layer.unitValueEur,
                    layer.asOfDate, layer.source, layer.note, layer.createdByName, layer.createdAt);
        }

        static OpeningLayer from(StockClosingService.OpeningLayerView layer) {
            return new OpeningLayer(layer.id(), layer.productId(), layer.sku(), layer.productName(), layer.quantity(),
                    layer.unitValueEur(), layer.asOfDate(), layer.source(), layer.note(), layer.createdByName(), layer.createdAt());
        }
    }

    public record ValuationRule(String method, String methodLabel, int effectiveFromYear, String ruleVersion, String text) {
        static ValuationRule from(StockValuationRuleEntity rule) {
            return rule == null ? null : new ValuationRule(rule.method, rule.methodLabel,
                    rule.effectiveFromYear == null ? 0 : rule.effectiveFromYear, rule.ruleVersion, rule.ruleText);
        }

        /** The rule as the closing copied it, so a final closing shows the wording it was made under. */
        static ValuationRule from(StockClosingEntity closing) {
            return new ValuationRule(closing.ruleMethod, closing.ruleMethodLabel,
                    closing.ruleEffectiveFromYear == null ? 0 : closing.ruleEffectiveFromYear, closing.ruleVersion, closing.ruleText);
        }
    }

    /* ----------------------------------------------------------- overview */

    public record ClosingSummary(long id, int closingYear, int versionNo, LocalDate closingDate, String status,
                                 boolean superseded, BigDecimal totalValueEur, BigDecimal estimatedEur, int blockerCount,
                                 int warningCount, String correctionReason, String finalizedByName, Instant finalizedAt,
                                 boolean hasFiles) {
        static ClosingSummary from(StockClosingEntity closing) {
            return new ClosingSummary(closing.id, closing.closingYear, closing.versionNo, closing.closingDate, closing.status,
                    closing.supersededById != null, closing.totalValueEur, closing.estimatedEur,
                    closing.blockerCount == null ? 0 : closing.blockerCount, closing.warningCount == null ? 0 : closing.warningCount,
                    closing.correctionReason, closing.finalizedByName, closing.finalizedAt,
                    closing.pdfStorageKey != null && closing.xlsxStorageKey != null);
        }
    }

    public record ClosingOverview(ValuationRule rule, List<ClosingSummary> closings) {
        static ClosingOverview from(StockClosingService.Overview overview) {
            return new ClosingOverview(ValuationRule.from(overview.rule()),
                    overview.closings().stream().map(ClosingSummary::from).toList());
        }
    }

    /* --------------------------------------------------------------- view */

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notice(String code, String severity, String segment, String message, Long productId, Long purchaseOrderId,
                         Long locationId, Long salesOrderId, Long movementId, String payee, BigDecimal amountEur) {
        static Notice from(ClosingNotices.Notice notice) {
            return new Notice(notice.code(), notice.severity(), notice.segment(), notice.message(), notice.productId(),
                    notice.purchaseOrderId(), notice.locationId(), notice.salesOrderId(), notice.movementId(), notice.payee(),
                    notice.amountEur());
        }
    }

    public record ClosingTotals(BigDecimal costValueEur, BigDecimal writeDownEur, BigDecimal ownValueEur, BigDecimal demoValueEur,
                                BigDecimal partnerIncludedEur, BigDecimal partnerExcludedEur, BigDecimal transitIncludedEur,
                                BigDecimal transitExcludedEur, BigDecimal invoicedOutEur, BigDecimal totalValueEur,
                                BigDecimal estimatedEur, int ownQuantity, int unvaluedQuantity) {
        static ClosingTotals from(StockClosingEntity closing) {
            return new ClosingTotals(nz(closing.costValueEur), nz(closing.writeDownEur), nz(closing.ownValueEur),
                    nz(closing.demoValueEur), nz(closing.partnerIncludedEur), nz(closing.partnerExcludedEur),
                    nz(closing.transitIncludedEur), nz(closing.transitExcludedEur), nz(closing.invoicedOutEur),
                    nz(closing.totalValueEur), nz(closing.estimatedEur), closing.ownQuantity == null ? 0 : closing.ownQuantity,
                    closing.unvaluedQuantity == null ? 0 : closing.unvaluedQuantity);
        }
    }

    public record ClosingLocation(long locationId, String locationName, String anchor, Long countId, Instant anchoredAt,
                                  String countedByName, boolean countAfterClosingDate, int correctionCount, int lineCount,
                                  int differenceCount, int movementCount, int reviewCount) {
        static ClosingLocation from(StockClosingService.LocationView location) {
            return new ClosingLocation(location.locationId(), location.locationName(), location.anchor(), location.countId(),
                    location.anchoredAt(), location.countedByName(), location.countAfterClosingDate(), location.correctionCount(),
                    location.lineCount(), location.differenceCount(), location.movementCount(), location.reviewCount());
        }
    }

    public record ClosingLayer(String block, int position, String source, String originSource, Integer originClosingYear,
                               Long purchaseOrderId, String orderNumber, String displayName, LocalDate receivedOn,
                               String openingSource, Long salesOrderId, Integer capacity, int quantity, BigDecimal unitValueEur,
                               BigDecimal unitGoodsEur, BigDecimal unitTransportEur, BigDecimal unitLogisticsEur,
                               BigDecimal unitSeparateEur, BigDecimal unitEstimatedEur, BigDecimal valueEur, BigDecimal estimatedEur,
                               int writeDownQuantity, BigDecimal writeDownEur) {
        static ClosingLayer from(StockClosingLayerEntity layer, Map<Long, Integer> closingYears) {
            return new ClosingLayer(layer.block, layer.position == null ? 0 : layer.position, layer.source, layer.originSource,
                    layer.originClosingId == null ? null : closingYears.get(layer.originClosingId), layer.purchaseOrderId,
                    layer.orderNumber, layer.displayName, layer.receivedOn, layer.openingSource, layer.salesOrderId, layer.capacity,
                    layer.quantity == null ? 0 : layer.quantity, layer.unitValueEur, layer.unitGoodsEur, layer.unitTransportEur,
                    layer.unitLogisticsEur, layer.unitSeparateEur, layer.unitEstimatedEur, layer.valueEur, layer.estimatedEur,
                    layer.writeDownQuantity == null ? 0 : layer.writeDownQuantity, nz(layer.writeDownEur));
        }
    }

    public record ClosingArticleLocation(long locationId, String locationName, String anchor, Long countId,
                                         Integer expectedQuantity, Integer countedQuantity, Integer countDifference,
                                         String countReasonCode, String countReasonLabel, String countReasonNote,
                                         String countedByName, Instant countedAt, Instant anchoredAt,
                                         boolean countAfterClosingDate, int anchorQuantity, int rollDelta, int closingQuantity,
                                         int ownQuantity, BigDecimal costValueEur, BigDecimal goodsEur, BigDecimal transportEur,
                                         BigDecimal logisticsEur, BigDecimal separateEur, BigDecimal openingEur,
                                         BigDecimal estimatedEur, BigDecimal writeDownEur, BigDecimal ownValueEur) {
        static ClosingArticleLocation from(StockClosingLineEntity line, int ownQuantity, StockClosingEntity closing) {
            return new ClosingArticleLocation(line.locationId, line.locationName, line.anchor, line.countId,
                    line.expectedQuantity, line.countedQuantity, line.countDifference, line.countReasonCode,
                    CountReason.labelOf(line.countReasonCode), line.countReasonNote, line.countedByName, line.countedAt,
                    line.anchoredAt, StockClosingService.countAfter(line.anchoredAt, line.anchor, closing),
                    line.anchorQuantity == null ? 0 : line.anchorQuantity, line.rollDelta == null ? 0 : line.rollDelta,
                    line.closingQuantity == null ? 0 : line.closingQuantity, ownQuantity, nz(line.costValueEur), nz(line.goodsEur),
                    nz(line.transportEur), nz(line.logisticsEur), nz(line.separateEur), nz(line.openingEur),
                    nz(line.estimatedEur), nz(line.writeDownEur), nz(line.ownValueEur));
        }
    }

    public record ClosingArticle(long productId, String sku, String productName, String categoryName, String unitKey,
                                 String salesUnit, Integer piecesPerUnit, boolean demo, boolean active, int countedQuantity,
                                 int rollDelta, int closingQuantity, int thirdPartyQuantity, int partnerQuantity,
                                 int invoicedOutQuantity, int ownQuantity, int unvaluedQuantity, BigDecimal costValueEur,
                                 BigDecimal goodsEur, BigDecimal transportEur, BigDecimal logisticsEur, BigDecimal separateEur,
                                 BigDecimal openingEur, BigDecimal estimatedEur, BigDecimal averageUnitEur,
                                 BigDecimal writeDownEur, BigDecimal ownValueEur, BigDecimal previousWriteDownEur, String status,
                                 List<ClosingLayer> layers, List<ClosingArticleLocation> locations) {
        static ClosingArticle from(StockClosingArticleEntity article, StockClosingService.View view) {
            List<StockClosingLineEntity> lines = view.lines().stream()
                    .filter(line -> Objects.equals(line.productId, article.productId)).toList();
            Map<Long, Integer> held = new LinkedHashMap<>();
            lines.forEach(line -> held.put(line.locationId, line.closingQuantity == null ? 0 : line.closingQuantity));
            /* Derived each time it is shown: the own pieces spread over the locations in proportion to what lies there. */
            Map<Long, Integer> own = FifoValuer.ownQuantityPerLocation(article.ownQuantity == null ? 0 : article.ownQuantity, held);
            return new ClosingArticle(article.productId, article.sku, article.productName, article.categoryName, article.unitKey,
                    article.salesUnit, article.piecesPerUnit, Boolean.TRUE.equals(article.demo), Boolean.TRUE.equals(article.active),
                    count(article.countedQuantity), count(article.rollDelta), count(article.closingQuantity),
                    count(article.thirdPartyQuantity), count(article.partnerQuantity), count(article.invoicedOutQuantity),
                    count(article.ownQuantity), count(article.unvaluedQuantity), nz(article.costValueEur), nz(article.goodsEur),
                    nz(article.transportEur), nz(article.logisticsEur), nz(article.separateEur), nz(article.openingEur),
                    nz(article.estimatedEur), article.averageUnitEur, nz(article.writeDownEur), nz(article.ownValueEur),
                    article.previousWriteDownEur, article.status,
                    view.layers().stream().filter(layer -> Objects.equals(layer.productId, article.productId))
                            .map(layer -> ClosingLayer.from(layer, view.closingYears())).toList(),
                    lines.stream().map(line -> ClosingArticleLocation.from(line, own.getOrDefault(line.locationId, 0), view.closing()))
                            .toList());
        }
    }

    public record Accrual(long decisionId, BigDecimal amountEur, boolean invoiceReceived, String reason, boolean stale) {}

    public record ClosingStream(String payee, String payeeLabel, String status, BigDecimal plannedEur, BigDecimal paidEur,
                                BigDecimal openEur, BigDecimal includedEur, BigDecimal estimatedEur, String state, Accrual accrual) {
        static ClosingStream of(StockClosingContainerEntity container, String payee, String status, BigDecimal planned,
                                BigDecimal paid, BigDecimal open, BigDecimal included, BigDecimal estimated, Index index) {
            String state = StockClosingService.streamState(paid, included, estimated);
            StockClosingDecisionEntity decision = StockClosingService.ROLE_PREVIOUS.equals(container.role) ? null
                    : index.accrual(container.purchaseOrderId, payee);
            /* On a container in transit only the supplier's amount is measured against the Afspraak. */
            boolean measured = !StockClosingService.ROLE_TRANSIT.equals(container.role) || "SUPPLIER".equals(payee);
            Accrual accrual = decision == null ? null : new Accrual(decision.id, decision.amountEur,
                    Boolean.TRUE.equals(decision.flag), decision.reason,
                    measured && nz(decision.basisAmountEur).compareTo(nz(open)) != 0);
            return new ClosingStream(payee, PayeeLabels.of(payee), status, nz(planned), nz(paid), nz(open), nz(included),
                    nz(estimated), state, accrual);
        }
    }

    public record ClosingLot(long productId, String sku, String productName, int orderedQuantity, int receivedQuantity,
                             int damagedQuantity, int laterLostQuantity, int billedQuantity, int goodsDivisor, int costDivisor,
                             int capacity, BigDecimal unitPriceEur, BigDecimal goodsKeyEur, BigDecimal transportKeyEur,
                             BigDecimal logisticsKeyEur, BigDecimal separateKeyEur, BigDecimal goodsEur, BigDecimal priceCreditEur,
                             BigDecimal transportEur, BigDecimal logisticsEur, BigDecimal separateEur, BigDecimal lotCostEur,
                             BigDecimal estimatedEur, BigDecimal unitGoodsEur, BigDecimal unitTransportEur,
                             BigDecimal unitLogisticsEur, BigDecimal unitSeparateEur, BigDecimal unitValueEur,
                             BigDecimal unitEstimatedEur, BigDecimal calcOriginEur, BigDecimal calcFreightEur,
                             BigDecimal calcDutyEur, BigDecimal calcDestinationEur, BigDecimal calcDutyRatePct,
                             BigDecimal previousUnitValueEur, String status) {
        static ClosingLot from(StockClosingLotEntity lot) {
            return new ClosingLot(lot.productId, lot.sku, lot.productName, count(lot.orderedQuantity), count(lot.receivedQuantity),
                    count(lot.damagedQuantity), count(lot.laterLostQuantity), count(lot.billedQuantity), count(lot.goodsDivisor),
                    count(lot.costDivisor), count(lot.capacity), lot.unitPriceEur, lot.goodsKeyEur, lot.transportKeyEur,
                    lot.logisticsKeyEur, lot.separateKeyEur, lot.goodsEur, lot.priceCreditEur, lot.transportEur, lot.logisticsEur,
                    lot.separateEur, lot.lotCostEur, lot.estimatedEur, lot.unitGoodsEur, lot.unitTransportEur, lot.unitLogisticsEur,
                    lot.unitSeparateEur, lot.unitValueEur, lot.unitEstimatedEur, lot.calcOriginEur, lot.calcFreightEur,
                    lot.calcDutyEur, lot.calcDestinationEur, lot.calcDutyRatePct, lot.previousUnitValueEur, lot.status);
        }
    }

    public record ClosingContainer(long purchaseOrderId, String orderNumber, String displayName, String supplierName, String role,
                                   String partnerName, LocalDate orderDate, LocalDate shippedOn, LocalDate receivedOn,
                                   LocalDate rateCutoffDate, String rateCutoffSource, String rateCutoffSourceLabel,
                                   Long ownershipDecisionId, String supplierIncoterm, String quantityBasis, String billedBasis,
                                   boolean hasShortage, BigDecimal cnyToUsd, BigDecimal usdToEurGoods, BigDecimal usdToEurTransport,
                                   boolean cif, boolean groupVariants, boolean separateInPiecePrice, String allocOrigin,
                                   String allocFreight, String allocDestination, String allocSeparate, String allocationLabel,
                                   String notes, List<ClosingStream> streams, BigDecimal supplierGoodsEur,
                                   BigDecimal supplierTransportEur, BigDecimal otherExcludedEur, BigDecimal priceCreditEur,
                                   BigDecimal lossCreditEur, BigDecimal exchangeDifferenceEur, BigDecimal enrosedCostExcludedEur,
                                   BigDecimal acquisitionEur, BigDecimal estimatedEur, List<Map<String, Object>> payments,
                                   List<Map<String, Object>> credits, BigDecimal missingAndDamagedCostEur, List<ClosingLot> lots) {
        static ClosingContainer from(StockClosingContainerEntity container, StockClosingService.View view, Index index) {
            List<StockClosingLotEntity> lots = view.lots().stream()
                    .filter(lot -> Objects.equals(lot.containerId, container.id)).toList();
            StockClosingDecisionEntity ownership = StockClosingService.ROLE_PREVIOUS.equals(container.role) ? null
                    : index.container(ClosingDecisionKind.OWNERSHIP_DATE, container.purchaseOrderId);
            return new ClosingContainer(container.purchaseOrderId, container.orderNumber, container.displayName,
                    container.supplierName, container.role, container.partnerName, container.orderDate, container.shippedOn,
                    container.receivedOn, container.rateCutoffDate, container.rateCutoffSource,
                    StockClosingService.borderLabel(container.rateCutoffSource), ownership == null ? null : ownership.id, container.supplierIncoterm,
                    container.quantityBasis, container.billedBasis,
                    lots.stream().anyMatch(lot -> count(lot.receivedQuantity) < count(lot.orderedQuantity)),
                    container.cnyToUsd, container.usdToEurGoods, container.usdToEurTransport, Boolean.TRUE.equals(container.cif),
                    Boolean.TRUE.equals(container.groupVariants), Boolean.TRUE.equals(container.separateInPiecePrice),
                    container.allocOrigin, container.allocFreight, container.allocDestination, container.allocSeparate,
                    PayeeLabels.allocationLabel(container.allocOrigin, container.allocFreight, container.allocDestination,
                            container.allocSeparate, container.groupVariants, container.separateInPiecePrice),
                    container.notes,
                    List.of(ClosingStream.of(container, "SUPPLIER", container.supplierStatus, container.supplierPlannedEur,
                                    container.supplierPaidEur, container.supplierOpenEur, container.supplierIncludedEur,
                                    container.supplierEstimatedEur, index),
                            ClosingStream.of(container, "LOGISTICS", container.logisticsStatus, container.logisticsPlannedEur,
                                    container.logisticsPaidEur, container.logisticsOpenEur, container.logisticsIncludedEur,
                                    container.logisticsEstimatedEur, index),
                            ClosingStream.of(container, "SEPARATE", container.separateStatus, container.separatePlannedEur,
                                    container.separatePaidEur, container.separateOpenEur, container.separateIncludedEur,
                                    container.separateEstimatedEur, index)),
                    nz(container.supplierGoodsEur), nz(container.supplierTransportEur), nz(container.otherExcludedEur),
                    nz(container.priceCreditEur), nz(container.lossCreditEur), nz(container.exchangeDifferenceEur),
                    nz(container.enrosedCostExcludedEur), nz(container.acquisitionEur), nz(container.estimatedEur),
                    StockClosingService.readJson(container.paymentsJson), StockClosingService.readJson(container.creditsJson),
                    StockClosingService.missingAndDamagedCost(lots), lots.stream().map(ClosingLot::from).toList());
        }
    }

    public record SeparateItem(long id, String kind, Long purchaseOrderId, Long salesOrderId, String documentNumber,
                               String documentName, LocalDate documentDate, String counterparty, Long productId, String sku,
                               String productName, Integer proposedQuantity, int quantity, Integer carvedQuantity, BigDecimal unitValueEur,
                               BigDecimal valueEur, BigDecimal estimatedEur, Boolean included, String choice,
                               LocalDate ownershipDate, LocalDate shippedOn, LocalDate receivedOn, BigDecimal paidUntilClosingEur,
                               String reason, boolean automatic, String decidedByName, Instant decidedAt, Long decisionId,
                               String supplierIncoterm, Boolean transportViaSupplier) {
        static SeparateItem from(StockClosingSeparateEntity row, List<StockClosingContainerEntity> containers) {
            StockClosingContainerEntity container = !FifoValuer.KIND_TRANSIT.equals(row.kind) ? null : containers.stream()
                    .filter(candidate -> Objects.equals(candidate.purchaseOrderId, row.purchaseOrderId)).findFirst().orElse(null);
            return new SeparateItem(row.id, row.kind, row.purchaseOrderId, row.salesOrderId, row.documentNumber, row.documentName,
                    row.documentDate, row.counterparty, row.productId, row.sku, row.productName, row.proposedQuantity,
                    count(row.quantity), row.carvedQuantity, row.unitValueEur, row.valueEur, row.estimatedEur, row.included, row.choice,
                    row.ownershipDate, row.shippedOn, row.receivedOn, row.paidUntilClosingEur, row.reason,
                    Boolean.TRUE.equals(row.automatic), row.decidedByName, row.decidedAt, row.decisionId,
                    container == null ? null : container.supplierIncoterm, container == null ? null : Boolean.TRUE.equals(container.cif));
        }
    }

    public record OlderInvoice(long salesOrderId, String number, LocalDate orderDate, String customerName, int quantity) {
        static OlderInvoice from(StockClosingSeparateEntity row) {
            return new OlderInvoice(row.salesOrderId, row.documentNumber, row.documentDate, row.counterparty, count(row.quantity));
        }
    }

    public record WriteDownRow(long decisionId, long productId, String sku, String productName, int layerPosition,
                               String layerLabel, int quantity, BigDecimal layerUnitEur, BigDecimal marketUnitEur,
                               BigDecimal amountEur, String reasonCode, String reasonLabel, String reason, String decidedByName,
                               Instant decidedAt) {
        static WriteDownRow from(StockClosingWriteDownEntity row) {
            return new WriteDownRow(row.decisionId, row.productId, row.sku, row.productName, count(row.layerPosition),
                    row.layerLabel, count(row.quantity), row.layerUnitEur, row.marketUnitEur, row.amountEur, row.reasonCode,
                    WriteDownReason.labelOf(row.reasonCode), row.reason, row.decidedByName, row.decidedAt);
        }
    }

    public record ClosingMovement(long movementId, long productId, String sku, String productName, long locationId,
                                  String locationName, Instant bookedAt, String kind, String kindLabel, String reference,
                                  String actor, int delta, int effectiveDelta, boolean noAnchor, LocalDate businessDate,
                                  String businessDateSource, boolean defaultApplied, String defaultNote, boolean applied,
                                  String appliedReason, boolean review, boolean removed, Long decisionId) {
        static ClosingMovement from(StockClosingMovementEntity row, Index index) {
            StockClosingDecisionEntity decision = index.movement(row.movementId);
            return new ClosingMovement(row.movementId, row.productId, row.sku, row.productName, row.locationId, row.locationName,
                    row.bookedAt, row.kind, row.kindLabel, row.refText, row.actor, count(row.delta), count(row.effectiveDelta),
                    Boolean.TRUE.equals(row.noAnchor), row.businessDate, row.businessDateSource,
                    Boolean.TRUE.equals(row.defaultApplied), row.defaultNote, Boolean.TRUE.equals(row.applied), row.appliedReason,
                    Boolean.TRUE.equals(row.review), Boolean.TRUE.equals(row.removed), decision == null ? null : decision.id);
        }
    }

    public record Decision(long id, String kind, String kindLabel, String subjectLabel, Long purchaseOrderId, Long salesOrderId,
                           Long productId, Long movementId, Long creditId, String payee, String choice, Boolean flag,
                           Integer quantity, BigDecimal unitValueEur, BigDecimal amountEur, LocalDate decisionDate,
                           String reasonCode, String reason, String counterparty, String decidedByName, Instant decidedAt) {
        static Decision from(StockClosingDecisionEntity decision, StockClosingService.View view) {
            return new Decision(decision.id, decision.kind, ClosingDecisionKind.labelOf(decision.kind), subject(decision, view),
                    decision.purchaseOrderId, decision.salesOrderId, decision.productId, decision.movementId, decision.creditId,
                    decision.payee, decision.choice, decision.flag, decision.quantity, decision.unitValueEur, decision.amountEur,
                    decision.decisionDate, decision.reasonCode, decision.reason, decision.counterparty, decision.decidedByName,
                    decision.decidedAt);
        }

        /** What the decision is about, in the words of the stored rows of the closing. */
        static String subject(StockClosingDecisionEntity decision, StockClosingService.View view) {
            List<String> parts = new ArrayList<>();
            if (decision.purchaseOrderId != null) {
                parts.add(view.containers().stream().filter(row -> Objects.equals(row.purchaseOrderId, decision.purchaseOrderId))
                        .map(row -> "Container " + row.displayName).findFirst().orElse("Container #" + decision.purchaseOrderId));
            }
            if (decision.payee != null) parts.add(PayeeLabels.of(decision.payee));
            if (decision.salesOrderId != null) {
                parts.add(view.separates().stream().filter(row -> Objects.equals(row.salesOrderId, decision.salesOrderId)
                                && row.documentNumber != null).map(row -> "Factuur " + row.documentNumber).findFirst()
                        .orElse("Factuur #" + decision.salesOrderId));
            }
            if (decision.productId != null) {
                parts.add(view.articles().stream().filter(row -> Objects.equals(row.productId, decision.productId))
                        .map(row -> row.productName).findFirst()
                        .orElseGet(() -> view.lots().stream().filter(row -> Objects.equals(row.productId, decision.productId))
                                .map(row -> row.productName).findFirst().orElse("Product #" + decision.productId)));
            }
            if (decision.movementId != null) {
                parts.add(view.movements().stream().filter(row -> Objects.equals(row.movementId, decision.movementId))
                        .map(row -> row.productName + " · " + (row.refText == null ? row.kindLabel : row.refText)).findFirst()
                        .orElse("Beweging #" + decision.movementId));
            }
            if (decision.creditId != null) parts.add("tegoed #" + decision.creditId);
            return parts.isEmpty() ? ClosingDecisionKind.labelOf(decision.kind) : String.join(" · ", parts);
        }
    }

    public record VersionChanges(long againstClosingId, int againstVersionNo, BigDecimal totalBeforeEur, BigDecimal totalAfterEur,
                                 List<ClosingVersionDiff.ArticleChange> articles, List<ClosingVersionDiff.LotChange> lots,
                                 List<ClosingVersionDiff.MovementChange> movements,
                                 List<ClosingVersionDiff.OpeningChange> openingLayers) {
        static VersionChanges from(ClosingVersionDiff.Changes changes) {
            return changes == null ? null : new VersionChanges(changes.againstClosingId(), changes.againstVersionNo(),
                    changes.totalBeforeEur(), changes.totalAfterEur(), changes.articles(), changes.lots(), changes.movements(),
                    changes.openingLayers());
        }
    }

    public record ClosingRef(long id, int closingYear, LocalDate closingDate, int versionNo) {}

    public record VersionRef(long id, int versionNo) {}

    public record Reason(String code, String label) {}

    public record ClosingView(long id, int closingYear, int versionNo, LocalDate closingDate, Instant cutoffAt, String status,
                              boolean superseded, Long supersedesId, Long supersededById, String correctionReason,
                              ClosingRef previousClosing, VersionRef previousClosingReplacedBy, VersionChanges versionChanges,
                              List<OlderInvoice> olderInvoices, ValuationRule rule, ClosingTotals totals, List<Notice> notices,
                              List<ClosingLocation> locations, List<ClosingArticle> articles, List<ClosingContainer> containers,
                              List<SeparateItem> separate, List<WriteDownRow> writeDowns, List<ClosingMovement> movements,
                              List<Decision> decisions, List<OpeningLayer> openingLayers, List<Reason> writeDownReasons,
                              Instant computedAt, String dataSha256, String finalizedByName, Instant finalizedAt,
                              String signerName, String pdfSha256, String xlsxSha256, List<ClosingSummary> versions,
                              boolean canFinalize, boolean canCorrect) {
        static ClosingView from(StockClosingService.View view) {
            StockClosingEntity closing = view.closing();
            Index index = Index.of(view.decisions());
            StockClosingEntity previous = view.previous();
            StockClosingEntity replacedBy = view.previousReplacedBy();
            return new ClosingView(closing.id, closing.closingYear, closing.versionNo, closing.closingDate, closing.cutoffAt,
                    closing.status, closing.supersededById != null, closing.supersedesId, closing.supersededById,
                    closing.correctionReason,
                    previous == null ? null : new ClosingRef(previous.id, previous.closingYear, previous.closingDate, previous.versionNo),
                    replacedBy == null ? null : new VersionRef(replacedBy.id, replacedBy.versionNo),
                    VersionChanges.from(view.versionChanges()),
                    view.separates().stream().filter(row -> FifoValuer.KIND_OLDER.equals(row.kind)).map(OlderInvoice::from).toList(),
                    ValuationRule.from(closing), ClosingTotals.from(closing),
                    view.notices().stream().map(Notice::from).toList(),
                    view.locations().stream().map(ClosingLocation::from).toList(),
                    view.articles().stream().map(article -> ClosingArticle.from(article, view)).toList(),
                    view.containers().stream().map(container -> ClosingContainer.from(container, view, index)).toList(),
                    view.separates().stream().filter(row -> !FifoValuer.KIND_OLDER.equals(row.kind))
                            .map(row -> SeparateItem.from(row, view.containers())).toList(),
                    view.writeDowns().stream().map(WriteDownRow::from).toList(),
                    view.movements().stream().map(row -> ClosingMovement.from(row, index)).toList(),
                    view.decisions().stream().map(decision -> Decision.from(decision, view)).toList(),
                    view.openingLayers().stream().map(OpeningLayer::from).toList(),
                    Arrays.stream(WriteDownReason.values()).map(reason -> new Reason(reason.code(), reason.label())).toList(),
                    closing.computedAt, closing.dataSha256, closing.finalizedByName, closing.finalizedAt, closing.signerName,
                    closing.pdfSha256, closing.xlsxSha256, view.versions().stream().map(ClosingSummary::from).toList(),
                    view.canFinalize(), view.canCorrect());
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? new BigDecimal("0.00") : value;
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }
}
