package be.enrosed.catalog.adapter.in.rest;

public record CatalogMigrationApplyRequest(
        CanonicalCatalogManifest manifest,
        boolean replaceExistingProducts,
        boolean deleteReferencingTestGraphs,
        /** Explicit clean slate: clears every application/business row before importing. */
        boolean fullReset,
        /** Must exactly match the server reset phrase when fullReset is true. */
        String confirmation
) {}
