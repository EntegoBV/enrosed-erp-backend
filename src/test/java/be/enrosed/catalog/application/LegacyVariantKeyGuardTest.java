package be.enrosed.catalog.application;

import be.enrosed.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retired webshop variant keys were replaced by ERP keys on 2026-09-22. The
 * prefix check backs up the database constraint and still rejects stale clients.
 */
class LegacyVariantKeyGuardTest {

    @Test
    void retiredWebshopVariantKeysAreRejectedRegardlessOfCaseAndSurroundingSpace() {
        for (String retired : new String[] {"shopify-123", " SHOPIFY-9 "}) {
            BusinessRuleException rejected = assertThrows(BusinessRuleException.class,
                    () -> ProductValidator.validateCanonicalVariantKey(retired), retired);
            assertTrue(rejected.getMessage().contains("webshop-variantcode"),
                    rejected.getMessage());
        }
    }

    @Test
    void erpVariantKeysAndMissingKeysAreAccepted() {
        assertDoesNotThrow(() -> ProductValidator.validateCanonicalVariantKey("erp-product-45"));
        assertDoesNotThrow(() -> ProductValidator.validateCanonicalVariantKey(null));
    }
}
