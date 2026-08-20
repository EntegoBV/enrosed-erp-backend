package be.enrosed.catalog.adapter.in.rest;

import java.util.List;
import java.util.Map;

public record CatalogMigrationPreflight(
        boolean valid,
        List<String> problems,
        List<String> warnings,
        int familyCount,
        int variantCount,
        int imageCount,
        long existingProducts,
        long purchaseOrderLineReferences,
        long salesOrderLineReferences,
        long salesPalletItemReferences,
        long quoteRevisionLineReferences,
        boolean deletionRequiresGraphCleanup,
        Map<String, Long> applicationTableRowCounts,
        String fullResetConfirmationRequired
) {}
