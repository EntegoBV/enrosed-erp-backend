package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Brand;
import be.enrosed.shared.DocumentFormat;
import be.enrosed.shared.PdfFonts;
import be.enrosed.shared.company.CompanyProfileService;
import be.enrosed.sourcing.domain.LandedCost;
import be.enrosed.sourcing.domain.PurchaseOrder;
import be.enrosed.sourcing.domain.PurchaseCostLabels;
import be.enrosed.sourcing.domain.Supplier;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;


/**
 * The purchase calculation as a PDF, to file away or to show at the table.
 *
 * Two views of the same sheet:
 *
 *  - **internal** — everything on it, including the desired extra revenue as
 *    its own line. That is the sheet you keep.
 *  - **customer view** — the same calculation without that line. The amount
 *    IS folded into the total, so the cost per piece matches what we use. A
 *    customer looking along sees where we land, not how much margin is in it.
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

    /**
     * @param showRevenue shows the desired extra revenue as its own line.
     *                    Off, it stays in the total but out of sight.
     */
    public Document render(PurchaseOrder order, LandedCost costing, Supplier supplier,
                           boolean showRevenue) {
        PurchaseCostLabels costLabels = PurchaseCostLabels.forOrder(order, supplier);
        String html = template
                .data("order", order)
                .data("costing", costing)
                .data("supplierName", supplier == null ? "-" : supplier.name())
                .data("supplierAddressLines", visibleSupplierAddress(supplier, showRevenue))
                .data("costLabels", costLabels)
                .data("unifiedUsdToEur", sameRate(order))
                .data("orderDate", DocumentFormat.be(order.orderDate()))
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
}
