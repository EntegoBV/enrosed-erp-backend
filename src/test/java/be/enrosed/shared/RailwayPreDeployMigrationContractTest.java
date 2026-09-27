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

    @Test
    void sharedSupplierApplicabilityIsAdditiveAndRegisteredBeforeAppStartup() throws IOException {
        Path migration = Path.of("docs/migrations/2026-09-13/shared-supplier-agreements-postgresql.sql");
        String sql = normalizedSql(migration);
        assertTrue(sql.contains("create table if not exists product_supplier_agreement_link"));
        assertTrue(sql.contains("product_id <> source_product_id"));
        assertTrue(sql.contains("fk_supplier_agreement_target"));
        assertTrue(sql.contains("fk_supplier_agreement_source"));
        assertNonDestructive(sql);
        assertTrue(Files.readString(Path.of("Dockerfile")).contains(migration.toString()));
        assertTrue(Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"))
                .contains("--file=/app/migrations/" + migration.getFileName()));
    }

    private static String normalizedSql(Path path) throws IOException {
        return Files.readString(path)
                .replaceAll("--[^\\r\\n]*", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    @Test
    void quotePriceVisibilityMigrationPreservesSavedChoiceAndRunsBeforeStartup() throws IOException {
        Path migration = Path.of("docs/migrations/2026-09-14/website-quote-settings-postgresql.sql");
        String sql = normalizedSql(migration);
        assertTrue(sql.contains("create table if not exists website_quote_settings"));
        assertTrue(sql.contains("prices_visible boolean not null default true"));
        assertTrue(sql.contains("on conflict (id) do nothing"));
        assertNonDestructive(sql);
        assertTrue(Files.readString(Path.of("Dockerfile")).contains(migration.toString()));
        assertTrue(Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"))
                .contains("--file=/app/migrations/" + migration.getFileName()));
    }

    @Test
    void websiteQuotePhotoMigrationAddsOnlyTheNullableFamilyChoiceBeforeStartup() throws IOException {
        Path migration = Path.of("docs/migrations/2026-09-22/family-website-quote-photo-postgresql.sql");
        String sql = normalizedSql(migration);
        assertTrue(sql.contains(
                "alter table product_family add column if not exists websitequotephotoid bigint"));
        assertFalse(sql.contains("not null"), "null keeps the automatic quote photo");
        assertFalse(sql.contains("update "), "existing families keep the automatic choice");
        assertNonDestructive(sql);
        assertTrue(Files.readString(Path.of("Dockerfile")).contains(migration.toString()));
        assertTrue(Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"))
                .contains("--file=/app/migrations/" + migration.getFileName()));
    }

    @Test
    void productUnitKeyMigrationAddsOnlyTheNullableUnitColumnBeforeStartup() throws IOException {
        Path migration = Path.of("docs/migrations/2026-09-22/product-unit-key-postgresql.sql");
        String sql = normalizedSql(migration);
        assertTrue(sql.contains(
                "alter table product add column if not exists packagingunitkey varchar(40)"));
        assertFalse(sql.contains("not null"), "null keeps the default unit stuk in code");
        assertFalse(sql.contains("default "), "the default lives in code, not in the column");
        assertFalse(sql.contains("update "), "existing products keep reading per stuk");
        assertNonDestructive(sql);
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String runner = Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"));
        assertTrue(dockerfile.contains(migration.toString()));
        assertTrue(runner.contains("--file=/app/migrations/" + migration.getFileName()));
        assertTrue(runner.indexOf("product-sales-unit-postgresql.sql")
                        < runner.indexOf(migration.getFileName().toString()),
                "the unit name follows the sales-unit column it complements");
    }

    @Test
    void creditNoteMigrationIsAdditiveAndRegisteredBeforeAppStartup() throws IOException {
        Path migration = Path.of("docs/migrations/2026-09-25/credit-notes-postgresql.sql");
        String sql = normalizedSql(migration);
        assertTrue(sql.contains("alter table sales_order add column if not exists credited_invoice_id bigint"));
        assertTrue(sql.contains("alter table sales_order add column if not exists credit_reason varchar(40)"));
        assertTrue(sql.contains("alter table sales_order add column if not exists goods_returned_at timestamp(6) with time zone"));
        assertTrue(sql.contains("alter table sales_payment add column if not exists offset_sales_order_id bigint"));
        assertTrue(sql.contains("alter table sales_payment add column if not exists offset_payment_id bigint"));
        assertTrue(sql.contains("alter table company_profile add column if not exists credit_note_number_prefix varchar(12)"));
        assertTrue(sql.contains("'creditnota'"), "the generated doctype check must learn the new value");
        assertTrue(sql.contains("'credit_note'"), "the generated deleted_item type check must learn the new value");
        assertTrue(sql.contains("drop constraint if exists quote_event_type_check"));
        assertFalse(sql.contains("stock_movement"), "the movement kind column is a plain varchar and needs nothing");
        assertNonDestructive(sql);
        assertTrue(Files.readString(Path.of("Dockerfile")).contains(migration.toString()));
        String runner = Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"));
        assertTrue(runner.contains("--file=/app/migrations/" + migration.getFileName()));
        assertTrue(runner.indexOf("catalog-export-order-postgresql.sql") < runner.indexOf(migration.getFileName().toString()),
                "the credit note columns land after every earlier script");
    }

    @Test
    void productTextVariantSizeMigrationRepairsOnlyUniformStaleCopiesOnceBeforeStartup()
            throws IOException {
        Path migration = Path.of(
                "docs/migrations/2026-09-27/product-text-variant-size-postgresql.sql");
        String sql = normalizedSql(migration);
        String marker = "'product-text-variant-size-2026-09-27'";
        assertTrue(sql.contains("create table if not exists catalog_data_patch"));
        assertTrue(sql.contains("perform pg_advisory_xact_lock(hashtextextended("
                + "'enrosed:product-text-variant-size-2026-09-27', 0))"));
        String guard = "if exists (select 1 from catalog_data_patch where patch_key = "
                + marker + ") then return; end if;";
        assertTrue(sql.contains(guard),
                "later deploys skip the repair, so sizes an editor saves afterwards stay");
        String update = "update product_text t set variantsize = s.base_size";
        assertTrue(sql.contains(update),
                "a stale copy takes the current base again, like the ERP does on a later edit");
        assertTrue(sql.indexOf(guard) < sql.indexOf("lock table product, product_text"),
                "a skipped run takes no table lock");
        assertTrue(sql.indexOf("lock table product, product_text in share row exclusive mode")
                < sql.indexOf(update), "the snapshot describes exactly the replaced values");
        assertTrue(sql.contains("insert into catalog_data_patch(patch_key, affected_rows, before_state) "
                + "select " + marker + ", count(*)"),
                "the marker is written in the same statement as the repair, also for zero rows");
        assertTrue(sql.contains("'before', r.before_size"),
                "every replaced value is kept for a rollback");
        assertTrue(sql.contains("'[]'::jsonb"), "before_state is never null");
        assertTrue(sql.contains("nullif(btrim(p.variantsize), '') is not null"),
                "a product without a base size keeps its per-language rows");
        String measurement = " ~* '^[0-9]+([.,][0-9]+)?"
                + "(\\s*[x×*]\\s*[0-9]+([.,][0-9]+)?){0,2}\\s*(mm|cm|m)?$'";
        assertTrue(sql.contains("btrim(t.variantsize)" + measurement),
                "only the measurements the backfill copies verbatim can be stale copies");
        assertTrue(sql.contains(
                "lower(btrim(t.variantsize)) <> lower(btrim(p.variantsize))"),
                "case and surrounding whitespace never make a row stale");
        assertTrue(sql.contains("not exists ( select 1 from product_text o "
                + "where o.product_id = t.product_id "
                + "and btrim(o.variantsize)" + measurement + " "
                + "and lower(btrim(o.variantsize)) <> lower(btrim(t.variantsize)))"),
                "measurements that differ between languages are deliberate localizations");
        assertFalse(sql.contains("colour"), "colour rows were seeded as translations");
        assertFalse(sql.contains("set variantsize = null"),
                "an empty row would be a strict-localization hole until the next restart");
        assertTrue(sql.contains("set local lock_timeout"));
        assertNonDestructive(sql);
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String runner = Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"));
        assertTrue(dockerfile.contains(migration.toString()));
        assertTrue(runner.contains("--file=/app/migrations/" + migration.getFileName()));
        assertTrue(runner.indexOf("catalog-export-order-postgresql.sql")
                        < runner.indexOf(migration.getFileName().toString()),
                "the repair runs after every schema migration it may depend on");
    }

    private static void assertNonDestructive(String sql) {
        assertFalse(sql.matches("(?s).*(drop\\s+(table|column)|truncate|delete\\s+from).*"));
    }
}
