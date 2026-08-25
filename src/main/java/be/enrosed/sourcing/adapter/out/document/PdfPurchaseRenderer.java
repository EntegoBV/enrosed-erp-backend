package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Brand;
import be.enrosed.shared.Currency;
import be.enrosed.shared.DocumentFormat;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfileService;
import be.enrosed.sourcing.application.PurchaseOrderService;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PaymentTerms;
import be.enrosed.sourcing.domain.PurchaseCostLabels;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseOrderStatus;
import be.enrosed.sourcing.domain.PurchasePayment;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;


/**
 * The purchase order as a PDF, to file away or to show at the table.
 *
 * Two views of the same sheet:
 *
 *  - **internal** — the complete dossier: every cost column, the payment
 *    plan against what was actually paid, the shipping moments and the
 *    diary. That is the sheet you keep.
 *  - **customer view** — the same calculation without the internals. The
 *    extra revenue IS folded into the total, so the cost per piece matches
 *    what we use. A customer looking along sees where we land, not how much
 *    margin is in it.
 *
 * Which of the two you get follows the double-tap switch on screen. That is
 * deliberately the same control: one state deciding what is visible, on
 * screen AND on paper. Two separate settings mean that sooner or later you
 * cover the screen but print the wrong sheet.
 */
@ApplicationScoped
public class PdfPurchaseRenderer {

    private final Template template;
    private final Brand brand;
    private final CompanyProfileService company;
    private final PdfFonts fonts;

    public PdfPurchaseRenderer(@Location("purchase.html") Template template, Brand brand,
                               CompanyProfileService company, PdfFonts fonts) {
        this.template = template;
        this.brand = brand;
        this.company = company;
        this.fonts = fonts;
    }

    public record Document(String filename, byte[] content, String contentType) {}

    /** One shipping moment on the sheet: what happened (or is expected) when. */
    public record Moment(String label, String date) {}

    /** One instalment of the payment plan, with whether its moment has come. */
    public record ScheduleRow(String label, String amount, boolean done) {}

    /** One registered payment, formatted for paper. */
    public record PaymentRow(String date, String label, String payee,
                             String amount, String original) {}

    /** The supplier/logistics/Enrosed split plus where the payments stand. */
    public record PayableView(String supplier, String logistics, String enrosed,
                              boolean freightInSupplierPrice, boolean ddp,
                              String paidSupplier, String paidLogistics,
                              String openSupplier, boolean overpaid) {}

    /**
     * @param showRevenue shows the desired extra revenue as its own line.
     *                    Off, it stays in the total but out of sight.
     */
    public Document render(PurchaseOrder order, LandedCost costing, Supplier supplier,
                           boolean showRevenue, List<PurchasePayment> payments,
                           PurchaseOrderService.Payable payable) {
        PurchaseCostLabels costLabels = PurchaseCostLabels.forOrder(order, supplier);
        String notes = order.notes() == null || order.notes().isBlank()
                ? null : order.notes().strip();
        String html = template
                .data("order", order)
                .data("costing", costing)
                .data("supplierName", supplier == null ? "-" : supplier.name())
                .data("supplierAddressLines", visibleSupplierAddress(supplier, showRevenue))
                .data("supplierContactLine", contactLine(supplier))
                .data("costLabels", costLabels)
                .data("unifiedUsdToEur", sameRate(order))
                .data("orderDate", DocumentFormat.be(order.orderDate()))
                .data("statusLabel", statusLabel(order.status()))
                .data("timeline", timeline(order))
                .data("paymentTermsLabel", order.paymentTerms().dutchLabel())
                .data("schedule", schedule(order, payable))
                .data("paymentRows", paymentRows(payments))
                .data("payableView", payableView(payments, payable))
                .data("hasExtraColumn", showRevenue && hasExtraColumn(costing))
                .data("notesText", showRevenue ? notes : null)
                .data("usdRateGoods", rate(order.usdToEurGoods()))
                .data("usdRateTransport", rate(order.usdToEurTransport()))
                .data("cnyRate", rate(order.cnyToUsd()))
                .data("logo", brand.logoDataUri())
                .data("company", company.get())
                .data("showRevenue", showRevenue)
                .render();

        String suffix = showRevenue ? "" : "-klantweergave";
        return new Document(order.number() + suffix + ".pdf", fonts.render(html),
                "application/pdf");
    }

    /**
     * Factory details are internal data. The customer-safe calculation keeps
     * showing the supplier name as it did historically, but gains no address.
     *
     * <p>The address is deliberately resolved live for this calculation PDF.
     * If this document later becomes an issued purchase order, supplier name
     * and address must be snapshotted at issue time.</p>
     */
    static List<String> visibleSupplierAddress(Supplier supplier, boolean showRevenue) {
        return showRevenue && supplier != null ? supplier.documentAddressLines() : List.of();
    }

    static boolean sameRate(PurchaseOrder order) {
        return order != null && order.usdToEurGoods() != null
                && order.usdToEurTransport() != null
                && order.usdToEurGoods().compareTo(order.usdToEurTransport()) == 0;
    }

    static String statusLabel(PurchaseOrderStatus status) {
        return switch (status) {
            case CONCEPT -> "Concept";
            case BESTELD -> "Besteld";
            case ONDERWEG -> "Onderweg";
            case ONTVANGEN -> "Ontvangen";
        };
    }

    static String contactLine(Supplier supplier) {
        if (supplier == null) return null;
        List<String> parts = new ArrayList<>();
        if (notBlank(supplier.contact())) parts.add(supplier.contact());
        if (notBlank(supplier.email())) parts.add(supplier.email());
        if (notBlank(supplier.phone())) parts.add(supplier.phone());
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /** The life of the box, in the order it happens. */
    static List<Moment> timeline(PurchaseOrder order) {
        List<Moment> moments = new ArrayList<>();
        if (order.orderDate() != null) {
            moments.add(new Moment("Besteld", DocumentFormat.be(order.orderDate())));
        }
        if (order.shippedOn() != null) {
            moments.add(new Moment("Vertrokken", DocumentFormat.be(order.shippedOn())));
        }
        if (order.receivedOn() != null) {
            moments.add(new Moment("Ontvangen", DocumentFormat.be(order.receivedOn())));
        } else if (order.expectedArrival() != null) {
            moments.add(new Moment("Verwacht", DocumentFormat.be(order.expectedArrival())));
        }
        return moments;
    }

    /** The agreed instalments, priced against the supplier's goods value. */
    static List<ScheduleRow> schedule(PurchaseOrder order, PurchaseOrderService.Payable payable) {
        if (payable == null || payable.supplierEur() == null) return List.of();
        List<ScheduleRow> rows = new ArrayList<>();
        for (PaymentTerms.Instalment instalment : order.paymentTerms().instalments()) {
            BigDecimal amount = payable.supplierEur().multiply(instalment.share())
                    .setScale(2, RoundingMode.HALF_UP);
            rows.add(new ScheduleRow(instalment.label(), DocumentFormat.eur(amount),
                    momentReached(order, instalment.due())));
        }
        return rows;
    }

    static boolean momentReached(PurchaseOrder order, PaymentTerms.Moment due) {
        return switch (due) {
            case ORDERED -> order.status() != PurchaseOrderStatus.CONCEPT;
            case SHIPPED -> order.shippedOn() != null
                    || order.status() == PurchaseOrderStatus.ONDERWEG
                    || order.status() == PurchaseOrderStatus.ONTVANGEN;
            case ARRIVED -> order.receivedOn() != null
                    || order.status() == PurchaseOrderStatus.ONTVANGEN;
        };
    }

    static List<PaymentRow> paymentRows(List<PurchasePayment> payments) {
        if (payments == null) return List.of();
        List<PaymentRow> rows = new ArrayList<>();
        for (PurchasePayment payment : payments) {
            String original = payment.currency() == null || payment.currency() == Currency.EUR
                    || payment.amount() == null
                    ? null
                    : DocumentFormat.amount(payment.amount()) + " " + payment.currency();
            rows.add(new PaymentRow(
                    payment.paidOn() == null ? "—" : DocumentFormat.be(payment.paidOn()),
                    notBlank(payment.label()) ? payment.label() : "Betaling",
                    payment.payee().dutchLabel(),
                    DocumentFormat.eur(payment.amountEur()),
                    original));
        }
        return rows;
    }

    static PayableView payableView(List<PurchasePayment> payments,
                                   PurchaseOrderService.Payable payable) {
        if (payable == null) return null;
        BigDecimal paidSupplier = paidTo(payments, PurchasePayment.Payee.SUPPLIER);
        BigDecimal paidLogistics = paidTo(payments, PurchasePayment.Payee.LOGISTICS);
        BigDecimal open = payable.supplierEur() == null
                ? BigDecimal.ZERO : payable.supplierEur().subtract(paidSupplier);
        return new PayableView(
                DocumentFormat.eur(payable.supplierEur()),
                DocumentFormat.eur(payable.logisticsEur()),
                DocumentFormat.eur(payable.enrosedEur()),
                payable.freightInSupplierPrice(), payable.ddp(),
                DocumentFormat.eur(paidSupplier), DocumentFormat.eur(paidLogistics),
                DocumentFormat.eur(open.abs()), open.signum() < 0);
    }

    static BigDecimal paidTo(List<PurchasePayment> payments, PurchasePayment.Payee payee) {
        if (payments == null) return BigDecimal.ZERO;
        return payments.stream()
                .filter(payment -> payment.payee() == payee)
                .map(PurchasePayment::amountEur)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static boolean hasExtraColumn(LandedCost costing) {
        return costing.lines().stream().anyMatch(line ->
                line.extraRevenueEur() != null && line.extraRevenueEur().signum() != 0);
    }

    /** An exchange rate exactly as entered: 0,8900 - never rounded. */
    static String rate(BigDecimal value) {
        return value == null ? null : value.toPlainString().replace('.', ',');
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
