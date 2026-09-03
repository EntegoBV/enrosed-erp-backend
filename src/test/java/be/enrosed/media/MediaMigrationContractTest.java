package be.enrosed.media;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaMigrationContractTest {
    @Test
    void migrationIsAdditiveIdempotentAndEnforcesReferenceInvariants() throws Exception {
        String sql = Files.readString(Path.of(
                "docs/migrations/2026-09-03/document-media-manager-postgresql.sql"));
        assertTrue(sql.contains("create table if not exists media_asset"));
        assertTrue(sql.contains("create table if not exists media_legacy_source"));
        assertTrue(sql.contains("fk_media_link_pinned_version"));
        assertTrue(sql.contains("fk_media_asset_current_version"));
        assertTrue(sql.contains("fk_media_legacy_source_version"));
        assertTrue(sql.contains("uk_media_version_id_asset"));
        assertTrue(sql.contains("fk_media_version_storage_blob"));
        assertTrue(sql.contains("fk_media_version_thumbnail_blob"));
        assertTrue(sql.contains("create table if not exists sales_document_media_snapshot"));
        assertTrue(sql.contains("fk_sales_document_media_order"));
        assertTrue(sql.contains("fk_sales_document_media_product"));
        assertTrue(sql.contains("fk_sales_document_media_blob"));
        assertTrue(sql.contains("fk_sales_document_media_version"));
        assertTrue(sql.contains("uk_sales_document_media_snapshot"));
        assertTrue(sql.contains("conrelid = 'media_link'::regclass"));
        assertTrue(sql.contains("where primary_slot = 1"));
        assertTrue(sql.contains("create index if not exists"));
    }
}
