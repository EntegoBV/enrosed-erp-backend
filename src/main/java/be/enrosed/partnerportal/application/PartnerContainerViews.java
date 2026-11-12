package be.enrosed.partnerportal.application;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/partner-portal/containers (round 2, contract section 5 and v2-F): every container of Enrosed's that is
 * not a concept, for any partner, as an allowlist of identity and logistics fields only.
 *
 * <p>Allowed: the PO number (as number and as name; the "Herkenbare naam" is free text and never sent), whether the
 * container is this partner's own, the status and the derived state word, the type, the order / departure / arrival
 * / receipt dates and a tracked move of the arrival, the ports, the location name (only for an own container or the
 * main warehouse), quantities in sales units and pieces, cartons, m³, fill, pallets, weight, and the product card's
 * sizes and packaging. Never: a price, cost, freight, duty, payment or amount, the supplier (id, name, reference,
 * version), notes or any other free text (alias, issue note, supplier note, description, tracking reference), the
 * partner percents, another partner's identity. Built by {@link PartnerContainerReader} from the domain records;
 * never serialise PurchaseOrder, PurchaseOrderLine, Product or LandedCost into these.</p>
 */
public final class PartnerContainerViews {

    private PartnerContainerViews() {}

    /** The pallet the ± pallets are counted on (sales settings, Palletberekening): B x D and the loaded height. */
    public record Pallet(BigDecimal widthCm, BigDecimal depthCm, BigDecimal maxHeightCm) {}

    /** B x D x H in cm, mapped from the product card (lengthCm = B, widthCm = D, heightCm = H); null when blank. */
    public record Size(BigDecimal b, BigDecimal d, BigDecimal h) {}

    /**
     * One product on a container. quantity in sales units (open: what is ordered now; received: what went on the
     * shelf), orderedQuantity only on a received container when it differs; pieces = quantity x piecesPerUnit;
     * cartons and cbm as the purchase screen counts them; pallets each product on its own pallets, null without a
     * fit; weightKg = cartons x carton weight, null without a carton weight.
     */
    public record ContainerLine(long productId, int quantity, Integer orderedQuantity, int pieces, int cartons,
                                BigDecimal cbm, Integer pallets, BigDecimal weightKg) {}

    /**
     * Sums of the lines. capacityCbm / fillPercent null only for a type without a capacity (groupage); pallets null
     * when no line has a fit, palletsUnknown = lines without one; weightKg null when no line has a weight,
     * weightUnknown / cbmUnknown = some line with cartons lacks a carton weight / size.
     */
    public record ContainerTotals(int products, int units, int pieces, int cartons, BigDecimal cbm,
                                  BigDecimal capacityCbm, BigDecimal fillPercent, Integer pallets, int palletsUnknown,
                                  BigDecimal weightKg, boolean weightUnknown, boolean cbmUnknown) {}

    /**
     * One container. status = the order's own (BESTELD | ONDERWEG | ONTVANGEN); state = the partner's word
     * (BESTELD | ONDERWEG | BINNENKORT | AANGEKOMEN, the rule of the frontend's product-incoming); due = the expected
     * date while it can still hold (null without one, once received, or long overdue); arrivalMovedFrom = the date
     * tracking moved the arrival from, only while not received and different; old = received (or archived) more than
     * 60 days ago or without a receipt date, the ones "Ook oude containers" adds; locationName null for another
     * partner's container unloaded anywhere but the main warehouse.
     */
    public record Container(long id, String number, String name, boolean own, String status, String state,
                            String containerType, String containerLabel, LocalDate orderDate, LocalDate shippedOn,
                            LocalDate expectedArrival, LocalDate due, LocalDate receivedOn, LocalDate arrivalMovedFrom,
                            boolean stockBooked, boolean old, String departurePort, String destinationPort,
                            String locationName, ContainerTotals totals, List<ContainerLine> lines) {}

    /**
     * ProductTile (unwrapped, so it reads as the same flat tile the rest of the portal gets) plus the product card's
     * specification: category, colour and size, the product's own size and weight, the packaging kind and size, the
     * omdoos (sales units per carton, size, weight) and the omdozen per pallet (null when unknown or not fitting).
     */
    public record ProductSpec(@JsonUnwrapped PartnerPortalViews.ProductTile tile, Long categoryId,
                              String categoryName, String colour, String variantSize, Size productCm,
                              BigDecimal productWeightKg, String packagingKind, Size packagingCm,
                              Integer unitsPerCarton, Size cartonCm, BigDecimal cartonWeightKg,
                              Integer cartonsPerPallet) {}

    /** Containers: open ones by arrival (as Komt eraan), then received ones newest first; products once each. */
    public record ContainerPage(LocalDate serverDate, Pallet pallet, List<Container> containers,
                                List<ProductSpec> products) {}
}
