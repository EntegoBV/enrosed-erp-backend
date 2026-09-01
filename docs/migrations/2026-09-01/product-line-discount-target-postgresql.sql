-- Product target for line-level discount tiers.
--
-- Deploy before application code when DB_SCHEMA_STRATEGY=validate. The column
-- remains nullable for order tiers and for legacy global LINE rows. Those old
-- LINE rows are deliberately preserved but ignored by pricing, so this rollout
-- is additive, rerunnable and compatible with the previous application.

begin;

-- IF EXISTS keeps the pre-deploy step compatible with a brand-new database
-- where Hibernate's update strategy still owns initial schema creation.
alter table if exists discount_tier
    add column if not exists product_id bigint;

-- PostgreSQL has no ADD CONSTRAINT IF NOT EXISTS. New product_id values are
-- validated by the application; the database also prevents duplicate product
-- thresholds without changing the nullable legacy/order rows.
do $migration$
begin
    if to_regclass('discount_tier') is not null then
        create index if not exists idx_discount_tier_scope_product
            on discount_tier (scope, product_id);

        if not exists (
            select 1
            from pg_constraint
            where conname = 'uk_discount_tier_scope_product_threshold'
              and conrelid = 'discount_tier'::regclass
        ) then
            alter table discount_tier
                add constraint uk_discount_tier_scope_product_threshold
                unique (scope, product_id, minQuantity);
        end if;
    end if;
end
$migration$;

commit;
