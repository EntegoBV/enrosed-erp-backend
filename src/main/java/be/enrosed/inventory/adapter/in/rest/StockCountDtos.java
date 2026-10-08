package be.enrosed.inventory.adapter.in.rest;

import be.enrosed.catalog.domain.StockMovement;
import be.enrosed.inventory.adapter.out.persistence.StockCountEntity;
import be.enrosed.inventory.adapter.out.persistence.StockCountLineEntity;
import be.enrosed.inventory.application.StockCountService;
import be.enrosed.inventory.domain.CountReason;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** The JSON of the count sessions, field for field what the count screens read and send. */
public final class StockCountDtos {

    private StockCountDtos() {}

    public record StartRequest(Integer countYear, Long locationId, String note, Long correctsCountId) {}

    public record CountLineWrite(Integer countedQuantity, String reasonCode, String reasonNote, Integer revision,
                                 Boolean rebase, Boolean documentsConfirmed) {
        StockCountService.LineWrite toWrite() {
            return new StockCountService.LineWrite(countedQuantity, reasonCode, reasonNote, revision, rebase, documentsConfirmed);
        }
    }

    public record AddLineRequest(Long productId) {}

    public record BookRequest(String checkToken) {}

    public record CountSummary(long id, int countYear, long locationId, String locationName, String status,
                               Long correctsCountId, String note, int lineCount, int countedCount, int differenceCount,
                               int missingReasonCount, String startedByName, Instant startedAt, String bookedByName,
                               Instant bookedAt) {
        static CountSummary from(StockCountService.Summary summary) {
            if (summary == null) return null;
            StockCountEntity count = summary.count();
            return new CountSummary(count.id, count.countYear, count.locationId, count.locationName, count.status,
                    count.correctsCountId, count.note, summary.lineCount(), summary.countedCount(),
                    summary.differenceCount(), summary.missingReasonCount(), count.startedByName, count.startedAt,
                    count.bookedByName, count.bookedAt);
        }
    }

    public record CountLocation(long locationId, String locationName, String kindLabel, boolean active,
                                int productsWithStock, CountSummary open, CountSummary booked, int correctionCount) {
        static CountLocation from(StockCountService.LocationState state) {
            return new CountLocation(state.location().id(), state.location().name(), state.location().kind().dutchLabel(),
                    state.location().active(), state.productsWithStock(), CountSummary.from(state.open()),
                    CountSummary.from(state.booked()), state.correctionCount());
        }
    }

    public record CountOverview(int year, List<Integer> years, List<CountLocation> locations, List<CountSummary> counts) {
        static CountOverview from(StockCountService.Overview overview) {
            return new CountOverview(overview.year(), overview.years(),
                    overview.locations().stream().map(CountLocation::from).toList(),
                    overview.counts().stream().map(CountSummary::from).toList());
        }
    }

    public record OpenDocument(String kind, long id, String number, int quantity) {
        static OpenDocument from(StockCountService.OpenDocument document) {
            return new OpenDocument(document.kind(), document.id(), document.number(), document.quantity());
        }
    }

    public record CountLine(long id, long productId, String sku, String productName, String categoryName, Long familyId,
                            String unitKey, String salesUnit, Integer piecesPerUnit, boolean addedByHand,
                            int liveQuantity, Integer expectedQuantity, Integer countedQuantity, Integer difference,
                            String reasonCode, String reasonLabel, String reasonNote, String countedByName,
                            Instant countedAt, int revision, boolean moved, Integer bookedQuantity,
                            List<OpenDocument> openDocuments, boolean documentsConfirmed) {
        static CountLine from(StockCountService.Line line) {
            StockCountLineEntity row = line.row();
            return new CountLine(row.id, row.productId, row.sku, row.productName, row.categoryName, row.familyId,
                    row.unitKey, row.salesUnit, row.piecesPerUnit, Boolean.TRUE.equals(row.addedByHand),
                    line.liveQuantity(), row.expectedQuantity, row.countedQuantity, row.difference, row.reasonCode,
                    CountReason.labelOf(row.reasonCode), row.reasonNote, row.countedByName, row.countedAt,
                    row.revision == null ? 0 : row.revision, line.moved(), row.bookedQuantity,
                    line.openDocuments().stream().map(OpenDocument::from).toList(),
                    Boolean.TRUE.equals(row.documentsConfirmed));
        }
    }

    public record Warnings(List<UnbookedContainer> unbookedContainers, List<UnshippedInvoice> unshippedInvoices,
                           int olderUnshippedInvoiceCount, List<OrphanLevel> orphanLevels) {
        public record UnbookedContainer(long purchaseOrderId, String number, String displayName, LocalDate receivedOn) {}

        public record UnshippedInvoice(long salesOrderId, String number, LocalDate orderDate) {}

        public record OrphanLevel(long productId, int quantity) {}

        static Warnings from(StockCountService.Warnings warnings) {
            return new Warnings(
                    warnings.unbookedContainers().stream().map(container -> new UnbookedContainer(
                            container.purchaseOrderId(), container.number(), container.displayName(), container.receivedOn())).toList(),
                    warnings.unshippedInvoices().stream().map(invoice -> new UnshippedInvoice(
                            invoice.salesOrderId(), invoice.number(), invoice.orderDate())).toList(),
                    warnings.olderUnshippedInvoiceCount(),
                    warnings.orphanLevels().stream().map(level -> new OrphanLevel(level.productId(), level.quantity())).toList());
        }
    }

    public record Reason(String code, String label, boolean noteRequired) {
        static List<Reason> all() {
            return Arrays.stream(CountReason.values())
                    .map(reason -> new Reason(reason.code(), reason.label(), reason.noteRequired())).toList();
        }
    }

    /** A session with everything its screen shows; the first fifteen fields are those of {@link CountSummary}. */
    public record CountView(long id, int countYear, long locationId, String locationName, String status,
                            Long correctsCountId, String note, int lineCount, int countedCount, int differenceCount,
                            int missingReasonCount, String startedByName, Instant startedAt, String bookedByName,
                            Instant bookedAt, Warnings warnings, List<Reason> reasons, List<CountLine> lines) {
        static CountView from(StockCountService.Session session) {
            CountSummary summary = CountSummary.from(session.summary());
            return new CountView(summary.id(), summary.countYear(), summary.locationId(), summary.locationName(),
                    summary.status(), summary.correctsCountId(), summary.note(), summary.lineCount(),
                    summary.countedCount(), summary.differenceCount(), summary.missingReasonCount(),
                    summary.startedByName(), summary.startedAt(), summary.bookedByName(), summary.bookedAt(),
                    Warnings.from(session.warnings()), Reason.all(),
                    session.lines().stream().map(CountLine::from).toList());
        }
    }

    public record BookingCheck(List<Uncounted> uncounted, List<Long> missingReasons, List<Long> openDocuments,
                               List<Moved> moved, List<Negative> negative, Summary summary, String checkToken) {
        public record Uncounted(long productId, Long lineId, String sku, String productName, int liveQuantity) {}

        public record Movement(Instant at, int delta, String kindLabel, String reference, String actor) {
            static Movement from(StockMovement movement) {
                return new Movement(movement.at(), movement.delta(), movement.kind().dutchLabel(), movement.reference(),
                        movement.actor());
            }
        }

        public record Moved(long lineId, long productId, String sku, String productName, int expectedQuantity,
                            int countedQuantity, int difference, int liveQuantity, int resultQuantity,
                            List<Movement> movements) {}

        public record Negative(long lineId, String productName, int resultQuantity) {}

        public record Summary(int lines, int equal, @JsonProperty("short") int shortLines, int shortUnits,
                              @JsonProperty("over") int overLines, int overUnits) {}

        static BookingCheck from(StockCountService.Check check) {
            StockCountService.Check.CheckSummary summary = check.summary();
            return new BookingCheck(
                    check.uncounted().stream().map(line -> new Uncounted(line.productId(), line.lineId(), line.sku(),
                            line.productName(), line.liveQuantity())).toList(),
                    check.missingReasons(), check.openDocuments(),
                    check.moved().stream().map(line -> new Moved(line.lineId(), line.productId(), line.sku(),
                            line.productName(), line.expectedQuantity(), line.countedQuantity(), line.difference(),
                            line.liveQuantity(), line.resultQuantity(),
                            line.movements().stream().map(Movement::from).toList())).toList(),
                    check.negative().stream().map(line -> new Negative(line.lineId(), line.productName(),
                            line.resultQuantity())).toList(),
                    new Summary(summary.lines(), summary.equal(), summary.shortLines(), summary.shortUnits(),
                            summary.overLines(), summary.overUnits()),
                    check.checkToken());
        }
    }
}
