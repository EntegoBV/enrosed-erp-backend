package be.enrosed.sourcing.adapter.out.document;

import be.enrosed.shared.Currency;
import be.enrosed.sourcing.domain.Supplier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfPurchaseRendererTest {

    @Test
    void addressIsAvailableOnlyToInternalPurchaseCalculation() {
        Supplier supplier = new Supplier(1L, "Factory Ltd", "CN", "Guangzhou",
                null, null, null, Currency.CNY, "EXW", "Guangzhou", 25, null,
                "Factory Road 1", "Baiyun District", "510000", "Guangdong");

        assertEquals(List.of(), PdfPurchaseRenderer.visibleSupplierAddress(supplier, false));
        assertEquals(List.of("Factory Road 1", "Baiyun District",
                        "510000 Guangzhou, Guangdong", "CHINA (CN)"),
                PdfPurchaseRenderer.visibleSupplierAddress(supplier, true));
    }
}
