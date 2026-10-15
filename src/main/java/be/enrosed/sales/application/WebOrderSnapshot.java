package be.enrosed.sales.application;

import be.enrosed.catalog.domain.OrderStep;
import be.enrosed.catalog.domain.Packaging;
import be.enrosed.sales.domain.FreightState;
import be.enrosed.sales.domain.PricedOrder;
import be.enrosed.sales.domain.SalesOrder;
import be.enrosed.shared.Language;
import be.enrosed.shared.Money;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the customer ordered, frozen as JSON on the website order: the lines
 * and totals of the ERP's own pricing of the saved document, never of the
 * preview. The customer keeps seeing this while staff work on the document,
 * and the invoice is compared with it. Delivery address and contact live in
 * the delivery row. No cost, margin, internal note or staff name belongs here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebOrderSnapshot(int v, int revision, String at, String language, String countryCode,
                               String fulfillment, String notes, List<Line> lines, Totals totals,
                               boolean complete) {

    public static final int VERSION = 1;
    /** The public limit of a website request. */
    public static final int MAX_LINES = 100;
    private static final int MAX_DESCRIPTION = 160;
    private static final ObjectMapper JSON = new ObjectMapper();

    public WebOrderSnapshot {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One ordered line. {@code quantity} counts sales units; {@code cartons} is the cartons it ships
     * in when that is a whole number of known cartons (or, sold per carton, rounded up), null otherwise.
     * {@code orderStep} (the product's "Verkocht per"), {@code unitsPerOrderStep}, {@code unitKey} and
     * {@code salesUnit} name the quantity; all four are null in an order frozen before order steps,
     * which reads as per carton, "stuk" and per piece. Kept as text so a value this code does not
     * know never makes the whole snapshot unreadable.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(Long productId, String sku, String description, Integer cartons, Integer piecesPerCarton,
                       int quantity, BigDecimal unitPrice, BigDecimal discountPct, BigDecimal net,
                       String orderStep, Integer unitsPerOrderStep, String unitKey, String salesUnit) {

        /** Sold per full carton: the step of every order frozen before order steps. */
        @JsonIgnore
        public boolean perCarton() {
            return orderStep == null || OrderStep.effective(orderStep) == OrderStep.CARTON;
        }

        /** "3 stolpen", "2 displays": the quantity in the words of its own unit and basis. */
        public String quantityText(Language language) {
            return SalesUnitText.quantity(salesUnit, unitKey, quantity, language);
        }
    }

    /** PICKUP, CALCULATED or TO_CONFIRM; with TO_CONFIRM the shipping and every total that needs it are null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Totals(BigDecimal goods, BigDecimal shipping, String shippingStatus, BigDecimal totalExclVat,
                         BigDecimal vatRatePct, BigDecimal vatAmount, BigDecimal totalInclVat, String vatTreatment) {}

    /**
     * The snapshot of a saved website order.
     *
     * @param piecesPerCarton  the carton content per product id; missing or 0 means unknown
     * @param requestedCartons the cartons the customer asked for, per product id, for lines whose
     *                         carton content is unknown (those are stored with quantity 0)
     * @param packagings       each product's packaging, for the unit and basis of its quantity;
     *                         a product missing here reads as plain pieces
     */
    public static WebOrderSnapshot of(SalesOrder saved, PricedOrder priced, int revision, Instant at, String language,
                                      String fulfillment, Map<Long, Integer> piecesPerCarton,
                                      Map<Long, Integer> requestedCartons, Map<Long, Packaging> packagings) {
        boolean cut = priced.lines().size() > MAX_LINES;
        List<Line> lines = priced.lines().stream().limit(MAX_LINES).map(line -> {
            int pieces = piecesPerCarton == null ? 0 : Math.max(0, piecesPerCarton.getOrDefault(line.productId(), 0));
            boolean perCarton = line.orderStep() == OrderStep.CARTON;
            /* Whole known cartons, or for a product sold per carton the cartons it ships in; never a floor. */
            Integer cartons = pieces > 0 && line.quantity() % pieces == 0 ? Integer.valueOf(line.quantity() / pieces)
                    : !perCarton ? null
                    : pieces > 0 ? Integer.valueOf((line.quantity() + pieces - 1) / pieces)
                    : requestedCartons == null ? null : requestedCartons.get(line.productId());
            boolean hasPrice = positive(line.unitPrice());
            String description = line.customerDescription() == null || line.customerDescription().isBlank()
                    ? line.description() : line.customerDescription();
            Packaging packaging = packagings == null ? null : packagings.get(line.productId());
            return new Line(line.productId(), line.sku(), cut(description), cartons, pieces > 0 ? pieces : null,
                    line.quantity(), hasPrice ? line.unitPrice() : null,
                    hasPrice ? Money.money(line.discountPct()) : null, hasPrice ? Money.money(line.net()) : null,
                    line.orderStep().name(), line.unitsPerOrderStep(),
                    packaging == null ? null : packaging.unitKey(),
                    packaging == null ? null : packaging.salesUnit().name());
        }).toList();

        PricedOrder.Totals totals = priced.totals();
        PricedOrder.Validation validation = priced.validation();
        boolean pickup = "EXW".equalsIgnoreCase(saved.incoterm());
        boolean freightOpen = saved.freight() == FreightState.TE_BEPALEN
                || validation.freightPricingIssue() != null && !validation.freightPricingIssue().isBlank();
        String shippingStatus = pickup ? "PICKUP"
                : freightOpen || !positive(totals.shippingTotal()) ? "TO_CONFIRM" : "CALCULATED";
        boolean toConfirm = "TO_CONFIRM".equals(shippingStatus);
        /* Exactly when the page showed the customer a total; in every other case it said "to be confirmed".
           A line sold per verpakking or per stuk has a known quantity without its carton content. */
        boolean complete = !cut && !lines.isEmpty() && !freightOpen && !toConfirm
                && lines.stream().allMatch(line -> line.unitPrice() != null && line.quantity() > 0
                        && (line.piecesPerCarton() != null || !line.perCarton()))
                && validation.productsWithoutCartonDimensions().isEmpty()
                && validation.productsWithoutPalletFit().isEmpty();
        Totals frozen = new Totals(Money.money(totals.goodsTotal()),
                toConfirm ? null : Money.money(totals.shippingTotal()), shippingStatus,
                toConfirm ? null : Money.money(totals.total()), Money.money(totals.vatRatePct()),
                toConfirm ? null : Money.money(totals.vatAmount()),
                toConfirm ? null : Money.money(totals.totalInclVat()),
                totals.vatTreatment() == null ? null : totals.vatTreatment().name());
        return new WebOrderSnapshot(VERSION, revision, at == null ? null : at.toString(), language,
                saved.countryCode(), fulfillment, saved.notes() == null || saved.notes().isBlank() ? null : saved.notes(),
                lines, frozen, complete);
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception exception) {
            throw new IllegalStateException("De bestelling kon niet worden vastgelegd", exception);
        }
    }

    /** Null for an empty or unreadable column: a caller then treats the ordered figures as unknown. */
    public static WebOrderSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, WebOrderSnapshot.class);
        } catch (Exception exception) {
            return null;
        }
    }

    /** When the snapshot was taken; null when the stored value cannot be read. */
    @JsonIgnore
    public Instant takenAt() {
        try {
            return at == null ? null : Instant.parse(at);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String cut(String value) {
        return value == null || value.length() <= MAX_DESCRIPTION ? value : value.substring(0, MAX_DESCRIPTION);
    }
}
