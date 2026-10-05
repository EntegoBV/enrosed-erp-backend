-- One-time repair of per-language variant sizes left stale by an edit of the base size.
--
-- The startup backfill copied product.variantSize verbatim into every language,
-- because the strict public projection wants an explicit row per language, and
-- every catalogue prints that row before the base. An edit of the base in the
-- ERP left the copies behind: the website kept printing 4.5*4.5cm in all nine
-- languages while the product said 4.8*4.8cm. From this release on the ERP
-- carries such copies along on every edit (Product.textsFollowingBaseChange);
-- this script repairs the copies that went stale before it.
--
-- Only the shape a backfill copy has is touched: a measurement or a number with
-- an optional unit (the values the backfill copies verbatim) that differs from
-- the current base, while every measurement row of that product carries that
-- same value. Measurements that differ between languages (5,5*6cm next to
-- 5.5*6cm), words and labels (Klein, Sondermaß, Set van 3, 25 εκ.) and products
-- without a base size are deliberate translations and stay.
--
-- The audit marker makes later deploys skip this script, so per-language sizes
-- an editor saves afterwards are never reset; before_state keeps every replaced
-- value. Hibernate maps the unquoted camelCase column as variantsize.
begin;
set local lock_timeout = '15s';
set local statement_timeout = '120s';

create table if not exists catalog_data_patch (
    patch_key varchar(120) primary key,
    applied_at timestamptz not null default now(),
    affected_rows integer not null,
    before_state jsonb not null
);

do $migration$
declare
    repaired integer;
begin
    -- Serialize the marker check even if this file is run outside the deployment runner.
    perform pg_advisory_xact_lock(hashtextextended('enrosed:product-text-variant-size-2026-09-27', 0));
    if exists (select 1 from catalog_data_patch
               where patch_key = 'product-text-variant-size-2026-09-27') then
        return;
    end if;

    -- The release still serving traffic may save a product meanwhile; the snapshot below
    -- must describe exactly the values this statement replaces.
    lock table product, product_text in share row exclusive mode;

    with stale as (
        select t.id, t.product_id, t.language, t.variantsize as before_size,
               btrim(p.variantsize) as base_size
          from product_text t
          join product p on p.id = t.product_id
         where nullif(btrim(p.variantsize), '') is not null
           and btrim(t.variantsize) ~* '^[0-9]+([.,][0-9]+)?(\s*[x×*]\s*[0-9]+([.,][0-9]+)?){0,2}\s*(mm|cm|m)?$'
           and lower(btrim(t.variantsize)) <> lower(btrim(p.variantsize))
           and not exists (
               select 1 from product_text o
                where o.product_id = t.product_id
                  and btrim(o.variantsize) ~* '^[0-9]+([.,][0-9]+)?(\s*[x×*]\s*[0-9]+([.,][0-9]+)?){0,2}\s*(mm|cm|m)?$'
                  and lower(btrim(o.variantsize)) <> lower(btrim(t.variantsize)))
    ), repaired_rows as (
        update product_text t
           set variantsize = s.base_size
          from stale s
         where t.id = s.id
        returning s.id, s.product_id, s.language, s.before_size, s.base_size
    )
    insert into catalog_data_patch(patch_key, affected_rows, before_state)
    select 'product-text-variant-size-2026-09-27', count(*),
           coalesce(jsonb_agg(jsonb_build_object(
               'id', r.id,
               'productId', r.product_id,
               'language', r.language,
               'before', r.before_size,
               'base', r.base_size) order by r.product_id, r.language), '[]'::jsonb)
      from repaired_rows r;

    select affected_rows into repaired from catalog_data_patch
     where patch_key = 'product-text-variant-size-2026-09-27';
    raise notice 'Variant size copies: % stale per-language sizes took the current base', repaired;
end
$migration$;

commit;
