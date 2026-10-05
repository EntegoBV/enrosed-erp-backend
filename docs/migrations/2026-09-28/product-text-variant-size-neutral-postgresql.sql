-- One-time retirement of the per-language variant size (the Maat).
--
-- The Maat is one language-neutral value from this release on: quotes, invoices,
-- purchase PDFs, the PDF catalogue, the photo export and the public website all
-- print product.variantSize as typed, in every language, and translations (the
-- revisioned endpoint, the CSV/Excel exchange, shared fields, duplicate) neither
-- read nor write a size of their own. Per-language copies kept going stale when
-- the base changed (the diamond displays printed 4.5*4.5cm while their Maat said
-- 4.8*4.8cm), so the owner decided the Maat is not translated at all.
--
-- 1. A product without a base Maat whose translations do carry a size keeps the
--    size the website showed first: the English row, then the Dutch one, then the
--    first other language. Its base takes that value, so a size that only ever
--    lived in a translation (an imported catalogue text) does not disappear.
--    Two cases keep their empty base, because there the translation is a copy the
--    previous release left behind, not a Maat (before 17ff7ff an edit of the base
--    kept the per-language copies):
--    - the activity log records a Maat edit of that product: an editor emptied it;
--    - an active product of the same family already has that colour and that size
--      (compared like FamilyVariantRules): the copy came along with a duplicate,
--      and taking it would give the family two identical options, which blocks
--      its website build and every further edit of the family.
--    Their old values stay in before_state.clearedSizes like every other size.
-- 2. Every product_text.variantsize is cleared.
-- 3. Translation rows left completely empty are deleted: no name, public name,
--    description or colour, the rule CatalogMapper and the translation endpoint
--    apply on every save (such a row says nothing and is dropped there too).
--
-- The column itself stays, so the schema remains compatible: Hibernate validation
-- and H2 dev files keep working and an older image still starts. That older image
-- does not render the cleared data correctly, though: its strict public projection
-- and its localization check want an explicit size row per language and report
-- every Maat as missing once the rows are empty (websiteBuildReady() off, strict
-- website builds refused). A website build started between this script and the
-- switch to the new release therefore fails; the new release's catalogue revision
-- differs, so it queues a fresh build on startup. Do not start or retry a website
-- build by hand while the deploy is in progress.
--
-- Rollback to an image before this release: before or right after starting it,
-- restore the rows and sizes from the marker, the deleted rows first:
--   insert into product_text (id, product_id, language, name, public_name, description, colour)
--   select (d->>'id')::bigint, (d->>'productId')::bigint, d->>'language', d->>'name',
--          d->>'publicName', d->>'description', d->>'colour'
--     from catalog_data_patch p, jsonb_array_elements(p.before_state->'deletedEmptyRows') d
--    where p.patch_key = 'product-text-variant-size-neutral-2026-09-28'
--   on conflict do nothing;
--   update product_text t set variantsize = c->>'variantSize'
--     from catalog_data_patch p, jsonb_array_elements(p.before_state->'clearedSizes') c
--    where p.patch_key = 'product-text-variant-size-neutral-2026-09-28'
--      and t.product_id = (c->>'productId')::bigint and t.language = c->>'language';
-- A promoted base can stay (the older image prints the row first). A Maat typed
-- under the new release has no rows: the older image's startup backfill seeds only
-- measurements and size codes, so a free-text Maat (Set van 3) stays missing for
-- its strict build until its translations are typed, and a Maat changed under the
-- new release gets its old per-language copies back and needs them retyped.
--
-- The audit marker makes later deploys skip this script; before_state keeps every
-- promoted base, cleared size and deleted row. The release still serving traffic
-- during the deploy may write a per-language size after this commit; the new
-- release never reads it and clears it on that product's next save. Hibernate maps
-- the unquoted camelCase columns as variantsize and familyid.
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
    maat_edited text[] := '{}';
    candidate_count integer;
    promoted_count integer;
    promoted jsonb;
    cleared_count integer;
    cleared jsonb;
    deleted_count integer;
    deleted jsonb;
begin
    -- Serialize the marker check even if this file is run outside the deployment runner.
    perform pg_advisory_xact_lock(hashtextextended('enrosed:product-text-variant-size-neutral-2026-09-28', 0));
    if exists (select 1 from catalog_data_patch
               where patch_key = 'product-text-variant-size-neutral-2026-09-28') then
        return;
    end if;

    -- The release still serving traffic may save a product meanwhile; the snapshot below
    -- must describe exactly the values this script promotes, clears and deletes.
    lock table product, product_text in share row exclusive mode;

    -- Products whose Maat an editor changed in the ERP (ProductService logs it as the
    -- variantSize field). Read after the lock, so no edit commits in between; guarded,
    -- so a database restored from before the activity log still migrates.
    if to_regclass('activity_log') is not null then
        execute $edited$
            select coalesce(array_agg(distinct a.entity_id::text), '{}'::text[])
              from activity_log a
             where a.entity_type = 'PRODUCT'
               and a.entity_id is not null
               and a.changes_json like '%"variantSize"%'
        $edited$ into maat_edited;
    end if;

    with source as (
        select distinct on (t.product_id)
               t.product_id, t.language, btrim(t.variantsize) as size, p.variantsize as before_base
          from product_text t
          join product p on p.id = t.product_id
         where nullif(btrim(p.variantsize), '') is null
           and nullif(btrim(t.variantsize), '') is not null
         order by t.product_id,
                  case t.language when 'EN' then 0 when 'NL' then 1 else 2 end,
                  t.language
    ), promotable as (
        select s.*
          from source s
          join product p on p.id = s.product_id
         where not (p.id::text = any(maat_edited))
           and not exists (
               select 1 from product o
                where o.familyid = p.familyid
                  and o.id <> p.id
                  and o.active
                  and nullif(btrim(lower(regexp_replace(o.colour, '\s+', ' ', 'g'))), '')
                      is not distinct from nullif(btrim(lower(regexp_replace(p.colour, '\s+', ' ', 'g'))), '')
                  and nullif(btrim(lower(regexp_replace(o.variantsize, '\s+', ' ', 'g'))), '')
                      = btrim(lower(regexp_replace(s.size, '\s+', ' ', 'g'))))
    ), promoted_rows as (
        update product p
           set variantsize = s.size
          from promotable s
         where p.id = s.product_id
        returning p.id, s.language, s.size, s.before_base
    )
    select (select count(*) from source),
           count(*),
           coalesce(jsonb_agg(jsonb_build_object(
               'productId', r.id,
               'before', r.before_base,
               'variantSize', r.size,
               'fromLanguage', r.language) order by r.id), '[]'::jsonb)
      into candidate_count, promoted_count, promoted
      from promoted_rows r;

    -- The self-join reads the value before the update, so before_state holds the old size.
    with cleared_rows as (
        update product_text t
           set variantsize = null
          from product_text b
         where b.id = t.id
           and b.variantsize is not null
        returning t.id, t.product_id, t.language, b.variantsize as before_size
    )
    select count(*),
           coalesce(jsonb_agg(jsonb_build_object(
               'id', c.id,
               'productId', c.product_id,
               'language', c.language,
               'variantSize', c.before_size) order by c.product_id, c.language), '[]'::jsonb)
      into cleared_count, cleared
      from cleared_rows c;

    with deleted_rows as (
        delete from product_text t
         where nullif(btrim(t.name), '') is null
           and nullif(btrim(t.public_name), '') is null
           and nullif(btrim(t.description), '') is null
           and nullif(btrim(t.colour), '') is null
           and nullif(btrim(t.variantsize), '') is null
        returning t.id, t.product_id, t.language, t.name, t.public_name, t.description, t.colour
    )
    select count(*),
           coalesce(jsonb_agg(jsonb_build_object(
               'id', d.id,
               'productId', d.product_id,
               'language', d.language,
               'name', d.name,
               'publicName', d.public_name,
               'description', d.description,
               'colour', d.colour) order by d.product_id, d.language), '[]'::jsonb)
      into deleted_count, deleted
      from deleted_rows d;

    insert into catalog_data_patch(patch_key, affected_rows, before_state)
    values ('product-text-variant-size-neutral-2026-09-28',
            promoted_count + cleared_count + deleted_count,
            jsonb_build_object(
                'promotedBaseSizes', promoted,
                'clearedSizes', cleared,
                'deletedEmptyRows', deleted));

    raise notice 'Language-neutral Maat: % base sizes filled from a translation (% left empty: Maat emptied in the ERP or option taken by a sibling), % per-language sizes cleared, % empty translation rows deleted',
        promoted_count, candidate_count - promoted_count, cleared_count, deleted_count;
end
$migration$;

commit;
