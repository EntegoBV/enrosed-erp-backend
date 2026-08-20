package be.enrosed.catalog.adapter.in.rest;

import java.util.List;
import java.util.Map;

public record CatalogMigrationResult(
        String importKey,
        boolean idempotent,
        int familiesApplied,
        int variantsApplied,
        int imagesApplied,
        int reusedImageBlobs,
        int deletedProducts,
        int deletedSalesOrders,
        int deletedPurchaseOrders,
        int conflictsRecorded,
        boolean fullReset,
        Map<String, Long> clearedRows,
        List<String> warnings
) {}
