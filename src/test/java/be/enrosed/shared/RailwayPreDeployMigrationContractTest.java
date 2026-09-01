package be.enrosed.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailwayPreDeployMigrationContractTest {

    private static final Path PHOTO_MIGRATION = Path.of(
            "docs/migrations/2026-09-01/product-supplier-agreement-photos-postgresql.sql");
    private static final Path DISCOUNT_MIGRATION = Path.of(
            "docs/migrations/2026-09-01/product-line-discount-target-postgresql.sql");

    @Test
    void railwayRunsTheAdditiveScriptsBeforeStartingTheValidatedApplication() throws IOException {
        JsonNode railway = new ObjectMapper().readTree(Path.of("railway.json").toFile());
        JsonNode commands = railway.path("deploy").path("preDeployCommand");
        assertTrue(commands.isArray());
        assertEquals(1, commands.size());
        assertEquals("/app/scripts/run-postgresql-schema-migrations.sh",
                commands.get(0).asText());

        String dockerfile = Files.readString(Path.of("Dockerfile"));
        assertTrue(dockerfile.contains("postgresql-client"));
        assertTrue(dockerfile.contains(PHOTO_MIGRATION.toString()));
        assertTrue(dockerfile.contains(DISCOUNT_MIGRATION.toString()));

        String runner = Files.readString(
                Path.of("scripts/run-postgresql-schema-migrations.sh"));
        assertTrue(runner.contains("--set=ON_ERROR_STOP=1"));
        assertTrue(runner.contains("pg_advisory_lock"));
        int photo = runner.indexOf(PHOTO_MIGRATION.getFileName().toString());
        int discount = runner.indexOf(DISCOUNT_MIGRATION.getFileName().toString());
        assertTrue(photo >= 0 && discount > photo,
                "the pre-existing missing table must be created before the new discount column");
    }

    @Test
    void supplierAgreementPhotoMigrationIsRerunnableAndNonDestructive() throws IOException {
        String sql = normalizedSql(PHOTO_MIGRATION);
        assertTrue(sql.contains("create table if not exists product_supplier_agreement_photo"));
        assertTrue(sql.contains("fk_supplier_agreement_photo_product"));
        assertTrue(sql.contains("to_regclass('product') is not null"),
                "a fresh update-managed database must not fail before Hibernate creates product");
        assertTrue(sql.contains("uq_product_supplier_agreement_photo_position"));
        assertTrue(sql.contains("create index if not exists ix_product_supplier_agreement_photo_scope"));
        assertNonDestructive(sql);
    }

    @Test
    void productDiscountMigrationAddsOnlyTheNullableTargetAndSupportingConstraints()
            throws IOException {
        String sql = normalizedSql(DISCOUNT_MIGRATION);
        assertTrue(sql.contains(
                "alter table if exists discount_tier add column if not exists product_id bigint"));
        assertTrue(sql.contains("to_regclass('discount_tier') is not null"),
                "a fresh update-managed database must leave initial table creation to Hibernate");
        assertTrue(sql.contains(
                "create index if not exists idx_discount_tier_scope_product"));
        assertTrue(sql.contains("uk_discount_tier_scope_product_threshold"));
        assertTrue(sql.contains("unique (scope, product_id, minquantity)"));
        assertFalse(sql.contains("product_id bigint not null"),
                "ORDER and inert legacy LINE rows must remain valid during the rollout");
        assertNonDestructive(sql);
    }

    private static String normalizedSql(Path path) throws IOException {
        return Files.readString(path)
                .replaceAll("--[^\\r\\n]*", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static void assertNonDestructive(String sql) {
        assertFalse(sql.matches("(?s).*(drop\\s+(table|column)|truncate|delete\\s+from).*"));
    }
}
