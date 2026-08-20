package be.enrosed.sourcing.domain;

import be.enrosed.shared.Currency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SupplierTest {

    @Test
    void formatsAUnicodeChineseAddressWithoutAssumingEuropeanPostalRules() {
        Supplier supplier = supplier(7L,
                "浙江省杭州市余杭区仓前街道欧美金融城 8 幢",
                "5 楼 A-512 · Yuhang District",
                "311100", "Zhejiang");

        assertEquals(List.of(
                "浙江省杭州市余杭区仓前街道欧美金融城 8 幢",
                "5 楼 A-512 · Yuhang District",
                "311100 Hangzhou, Zhejiang",
                "CHINA (CN)"), supplier.documentAddressLines());
    }

    @Test
    void legacyConstructorKeepsNewAddressFieldsNullable() {
        Supplier legacy = new Supplier(7L, "Leverancier", "CN", "Yiwu",
                null, null, null, Currency.USD, "FOB", "Ningbo", 30, null);

        assertNull(legacy.addressLine1());
        assertNull(legacy.addressLine2());
        assertNull(legacy.postalCode());
        assertNull(legacy.region());
        assertEquals(List.of("Yiwu", "CHINA (CN)"), legacy.documentAddressLines());
    }

    @Test
    void pathIdCopyDoesNotDropAddress() {
        Supplier request = supplier(null, "No. 18", "Building B", "310000", "Zhejiang");

        Supplier updated = request.withId(42L);

        assertEquals(42L, updated.id());
        assertEquals(request.addressLine1(), updated.addressLine1());
        assertEquals(request.addressLine2(), updated.addressLine2());
        assertEquals(request.postalCode(), updated.postalCode());
        assertEquals(request.region(), updated.region());
    }

    private static Supplier supplier(Long id, String addressLine1, String addressLine2,
                                     String postalCode, String region) {
        return new Supplier(id, "Hangzhou Flowers Co., Ltd", "CN", "Hangzhou",
                "Li Wei", "li@example.cn", "+86 571 0000 0000", Currency.CNY,
                "EXW", "Ningbo", 35, null,
                addressLine1, addressLine2, postalCode, region);
    }
}
