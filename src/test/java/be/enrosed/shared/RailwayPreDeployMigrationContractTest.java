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

    @Test
    void languageNeutralMaatMigrationClearsEveryPerLanguageSizeOnceBeforeStartup()
            throws IOException {
        Path migration = Path.of(
                "docs/migrations/2026-09-28/product-text-variant-size-neutral-postgresql.sql");
        String sql = normalizedSql(migration);
        String marker = "'product-text-variant-size-neutral-2026-09-28'";
        assertTrue(sql.contains("create table if not exists catalog_data_patch"));
        assertTrue(sql.contains("perform pg_advisory_xact_lock(hashtextextended("
                + "'enrosed:product-text-variant-size-neutral-2026-09-28', 0))"));
        String guard = "if exists (select 1 from catalog_data_patch where patch_key = "
                + marker + ") then return; end if;";
        assertTrue(sql.contains(guard), "later deploys skip the one-shot retirement");
        String lock = "lock table product, product_text in share row exclusive mode";
        assertTrue(sql.indexOf(guard) < sql.indexOf(lock), "a skipped run takes no table lock");

        String promote = "update product p set variantsize = s.size from promotable s";
        assertTrue(sql.contains(promote), "a size that only lived in a translation survives");
        assertTrue(sql.contains("where nullif(btrim(p.variantsize), '') is null "
                        + "and nullif(btrim(t.variantsize), '') is not null"),
                "only a product without a base Maat takes one, a filled base is never rewritten");
        assertTrue(sql.contains("case t.language when 'en' then 0 when 'nl' then 1 else 2 end"),
                "the promoted size is the one the website showed first: English, then Dutch");
        String edited = "if to_regclass('activity_log') is not null then execute $edited$ "
                + "select coalesce(array_agg(distinct a.entity_id::text), '{}'::text[]) "
                + "from activity_log a where a.entity_type = 'product' "
                + "and a.entity_id is not null "
                + "and a.changes_json like '%\"variantsize\"%' $edited$ into maat_edited; end if;";
        assertTrue(sql.contains(edited),
                "a Maat an editor emptied stays empty: its translations are copies left behind");
        assertTrue(sql.indexOf(lock) < sql.indexOf(edited),
                "the edit history is read after the lock, so no edit commits in between");
        String productService = Files.readString(Path.of(
                "src/main/java/be/enrosed/catalog/application/ProductService.java"));
        assertTrue(productService.contains("ACTIVITY_ENTITY = \"PRODUCT\"")
                        && productService.contains(".add(\"variantSize\", "),
                "the migration finds Maat edits by the entity type and field the ERP logs");
        String rawSql = Files.readString(migration);
        assertTrue(rawSql.contains("a.entity_type = 'PRODUCT'")
                        && rawSql.contains("a.changes_json like '%\"variantSize\"%'"),
                "string comparison and LIKE are case-sensitive in PostgreSQL");
        assertTrue(sql.contains("where not (p.id::text = any(maat_edited))"),
                "no promotion for a product whose Maat was edited in the ERP");
        assertTrue(sql.contains("and not exists ( select 1 from product o "
                        + "where o.familyid = p.familyid and o.id <> p.id and o.active "
                        + "and nullif(btrim(lower(regexp_replace(o.colour, '\\s+', ' ', 'g'))), '') "
                        + "is not distinct from "
                        + "nullif(btrim(lower(regexp_replace(p.colour, '\\s+', ' ', 'g'))), '') "
                        + "and nullif(btrim(lower(regexp_replace(o.variantsize, '\\s+', ' ', 'g'))), '') "
                        + "= btrim(lower(regexp_replace(s.size, '\\s+', ' ', 'g'))))"),
                "a promoted Maat never gives a family two identical colour and size options");

        String clear = "update product_text t set variantsize = null from product_text b "
                + "where b.id = t.id and b.variantsize is not null "
                + "returning t.id, t.product_id, t.language, b.variantsize as before_size";
        assertTrue(sql.contains(clear), "every per-language size goes, its old value is kept");
        String delete = "delete from product_text t "
                + "where nullif(btrim(t.name), '') is null "
                + "and nullif(btrim(t.public_name), '') is null "
                + "and nullif(btrim(t.description), '') is null "
                + "and nullif(btrim(t.colour), '') is null "
                + "and nullif(btrim(t.variantsize), '') is null";
        assertTrue(sql.contains(delete),
                "only rows the mapper would drop go: nothing in any column a row can hold");
        assertTrue(sql.indexOf(lock) < sql.indexOf(promote)
                        && sql.indexOf(promote) < sql.indexOf(clear)
                        && sql.indexOf(clear) < sql.indexOf(delete),
                "promote before clearing, clear before deleting the rows left empty");
        assertEquals(1, sql.split("delete from", -1).length - 1, "one delete, of empty rows only");

        assertTrue(sql.contains("insert into catalog_data_patch(patch_key, affected_rows, before_state) "
                + "values (" + marker), "the marker is written, also when nothing changed");
        assertTrue(sql.contains("'promotedbasesizes', promoted")
                        && sql.contains("'clearedsizes', cleared")
                        && sql.contains("'deletedemptyrows', deleted"),
                "every promoted base, cleared size and deleted row is kept for a rollback");
        assertTrue(sql.contains("'[]'::jsonb"), "before_state lists are never null");
        assertTrue(sql.contains("set local lock_timeout"));
        assertFalse(sql.matches("(?s).*(drop\\s+(table|column)|truncate|alter\\s+table).*"),
                "the column stays: the schema remains compatible with validation, H2 and a rollback");

        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String runner = Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"));
        assertTrue(dockerfile.contains(migration.toString()));
        assertTrue(runner.contains("--file=/app/migrations/" + migration.getFileName()));
        assertTrue(runner.indexOf("--file=/app/migrations/product-text-variant-size-postgresql.sql")
                        < runner.indexOf(migration.getFileName().toString()),
                "the retirement runs after the 2026-09-27 repair of stale copies");
        assertTrue(runner.indexOf(migration.getFileName().toString())
                        < runner.indexOf("--file=/app/migrations/prospects-postgresql.sql"),
                "the existing catalogue repair remains before the new prospect schema");
    }

    @Test
    void privateProspectSchemaIsAdditiveAndRegisteredBeforeStartup() throws IOException {
        Path migration = Path.of("docs/migrations/2026-09-28/prospects-postgresql.sql");
        String sql = normalizedSql(migration);
        assertTrue(sql.contains("create table if not exists prospect ("));
        assertTrue(sql.contains("create table if not exists prospect_activity ("));
        assertTrue(sql.contains("create table if not exists prospect_outreach_lock ("));
        assertTrue(sql.contains("constraint uq_prospect_activity_external unique(external_id)"));
        assertTrue(sql.contains("insert into prospect_outreach_lock(id) values (1) on conflict (id) do nothing"));
        assertNonDestructive(sql);
        assertTrue(Files.readString(Path.of("Dockerfile")).contains(migration.toString()));
        String runner = Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"));
        assertTrue(runner.contains("--file=/app/migrations/" + migration.getFileName()));
        assertTrue(runner.indexOf("--file=/app/migrations/" + migration.getFileName())
                        < runner.indexOf("--file=/app/migrations/purchase-supplier-credit-postgresql.sql"),
                "the prospect schema keeps its place before the container round of 2026-09-29");
    }

    @Test
    void purchaseSupplierCreditTableIsAdditiveAndRegisteredLastBeforeStartup() throws IOException {
        Path migration = Path.of("docs/migrations/2026-09-29/purchase-supplier-credit-postgresql.sql");
        String sql = normalizedSql(migration);
        assertTrue(sql.contains("create table if not exists purchase_supplier_credit ("));
        assertTrue(sql.contains("id bigint generated by default as identity primary key"));
        assertTrue(sql.contains("order_id bigint not null"));
        assertTrue(sql.contains("noted_on date not null"));
        assertTrue(sql.contains("amount numeric(19,2) not null check (amount > 0)"));
        assertTrue(sql.contains("currency varchar(8) not null"));
        assertTrue(sql.contains("amount_eur numeric(19,2) not null check (amount_eur > 0)"));
        assertTrue(sql.contains("reason varchar(16) not null"));
        assertTrue(sql.contains("note varchar(500),"));
        assertTrue(sql.contains("status varchar(16) not null"));
        assertTrue(sql.contains("settled_on date,"));
        assertTrue(sql.contains("offset_order_id bigint,"));
        assertTrue(sql.contains("offset_payment_id bigint constraint uq_purchase_supplier_credit_offset_payment unique"),
                "one offset payment can settle at most one credit");
        assertTrue(sql.contains("recorded_at timestamptz not null"));
        assertTrue(sql.contains("create index if not exists purchase_supplier_credit_order_idx "
                + "on purchase_supplier_credit (order_id)"));
        assertTrue(sql.contains("create index if not exists purchase_supplier_credit_offset_idx "
                + "on purchase_supplier_credit (offset_order_id)"));
        assertFalse(sql.contains("alter table"), "no existing table changes for a supplier credit");
        assertFalse(sql.contains("update "), "no existing row is rewritten");
        assertNonDestructive(sql);
        String entities = Files.readString(Path.of(
                "src/main/java/be/enrosed/sourcing/adapter/out/persistence/SourcingEntities.java"));
        assertTrue(entities.contains("@UniqueConstraint(name = \"uq_purchase_supplier_credit_offset_payment\""),
                "the entity names the unique constraint like the migration, so a schema update adds no second one");
        for (String column : new String[] {"order_id", "noted_on", "amount", "currency", "amount_eur", "reason",
                "note", "status", "settled_on", "offset_order_id", "offset_payment_id", "actor", "recorded_at"}) {
            assertTrue(entities.contains("@Column(name = \"" + column + "\""),
                    "the entity names " + column + " explicitly, so validation finds the migrated column");
        }
        assertTrue(Files.readString(Path.of("Dockerfile")).contains(migration.toString()));
        String runner = Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"));
        assertTrue(runner.contains("--file=/app/migrations/" + migration.getFileName()));
        assertTrue(runner.indexOf("--file=/app/migrations/prospects-postgresql.sql")
                        < runner.indexOf(migration.getFileName().toString()),
                "the new table lands after every earlier script");
    }

    @Test
    void cifFlagIsANullableColumnAndTheTermCheckLearnsFreightBeforeStartup() throws IOException {
        Path migration = Path.of("docs/migrations/2026-09-29/purchase-order-freight-via-supplier-postgresql.sql");
        String sql = normalizedSql(migration);
        assertTrue(sql.contains("alter table purchase_order add column if not exists freight_via_supplier boolean;"));
        assertFalse(sql.contains("freight_via_supplier boolean not null"), "null reads as no");
        assertFalse(sql.contains("default"), "no default: existing containers stay as they are");
        assertFalse(sql.contains("update "), "nothing is backfilled");
        assertTrue(sql.contains("to_regclass('purchase_payment')"));
        assertTrue(sql.contains("a.attname in ('instalment_due', 'instalmentdue')"));
        assertTrue(sql.contains("position('''freight''' in pg_get_expr(c.conbin, c.conrelid)) = 0"),
                "only a check that does not know the new term yet is widened, so a rerun changes nothing");
        assertTrue(sql.contains("check ((%s) or %i = %l) not valid"), "the old expression is kept and extended");
        assertTrue(sql.contains("'freight'"));
        assertTrue(sql.contains("set local lock_timeout"));
        assertNonDestructive(sql);
        String entities = Files.readString(Path.of(
                "src/main/java/be/enrosed/sourcing/adapter/out/persistence/SourcingEntities.java"));
        assertTrue(entities.contains("@Column(name = \"freight_via_supplier\") public Boolean freightViaSupplier;"));
        assertTrue(Files.readString(Path.of(
                "src/main/java/be/enrosed/sourcing/adapter/out/persistence/AllocationDevSchemaFix.java"))
                .contains("\"instalment_due\""), "the developer's H2 enum column learns FREIGHT too");
        assertTrue(Files.readString(Path.of("Dockerfile")).contains(migration.toString()));
        String runner = Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"));
        assertTrue(runner.contains("--file=/app/migrations/" + migration.getFileName()));
        assertTrue(runner.indexOf("--file=/app/migrations/purchase-payment-instalment-postgresql.sql")
                        < runner.indexOf(migration.getFileName().toString()),
                "the term column exists before its check is widened");
        assertTrue(runner.indexOf("--file=/app/migrations/purchase-supplier-credit-postgresql.sql")
                        < runner.indexOf(migration.getFileName().toString()),
                "the container round lands in its commit order");
    }

    @Test
    void salesAdvanceBillingIsOneAdditiveSideTableRegisteredLast() throws IOException {
        Path migration = Path.of("docs/migrations/2026-09-30/sales-advance-billing-postgresql.sql");
        String sql = normalizedSql(migration);
        assertTrue(sql.contains("create table if not exists sales_advance_billing ("));
        assertTrue(sql.contains("sales_order_id bigint primary key"));
        assertTrue(sql.contains("quote_id bigint not null"));
        assertTrue(sql.contains("stage varchar(16) not null"));
        assertTrue(sql.contains("percentage numeric(9,4),"));
        assertTrue(sql.contains("amount_excl_eur numeric(19,2) not null"));
        assertTrue(sql.contains("deductions_json text,"));
        assertTrue(sql.contains("vat_treatment varchar(64),"));
        assertTrue(sql.contains("vat_rate_pct numeric(9,4),"));
        assertTrue(sql.contains("created_at timestamptz not null"));
        assertTrue(sql.contains("create index if not exists sales_advance_billing_quote_idx on sales_advance_billing (quote_id)"));
        assertFalse(sql.contains("check"), "the stage is a plain string, never an enum check");
        assertFalse(sql.contains("alter table"), "sales_order stays as it is");
        assertFalse(sql.contains("update "), "no existing row is rewritten");
        assertNonDestructive(sql);
        String entity = Files.readString(Path.of(
                "src/main/java/be/enrosed/sales/adapter/out/persistence/SalesAdvanceBillingEntity.java"));
        for (String column : new String[] {"sales_order_id", "quote_id", "stage", "percentage", "amount_excl_eur",
                "deductions_json", "vat_treatment", "vat_rate_pct", "created_at"}) {
            assertTrue(entity.contains("@Column(name = \"" + column + "\""),
                    "the entity names " + column + " explicitly, so validation finds the migrated column");
        }
        assertTrue(entity.contains("@Table(name = \"sales_advance_billing\""));
        assertFalse(entity.contains("@Enumerated"), "no enum column that Hibernate would guard with a check");
        assertTrue(Files.readString(Path.of("Dockerfile")).contains(migration.toString()));
        String runner = Files.readString(Path.of("scripts/run-postgresql-schema-migrations.sh"));
        assertTrue(runner.contains("--file=/app/migrations/" + migration.getFileName()));
        assertTrue(runner.indexOf("--file=/app/migrations/glass-box-twelve-roses-postgresql.sql")
                        < runner.indexOf(migration.getFileName().toString()),
                "the new table lands after every earlier script");
    }

    private static void assertNonDestructive(String sql) {
        assertFalse(sql.matches("(?s).*(drop\\s+(table|column)|truncate|delete\\s+from).*"));
    }
}
