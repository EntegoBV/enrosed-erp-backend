-- Retire Shopify identities without replacing any ERP product, photo or commercial row.
-- Source URLs, raw observations and import evidence remain historical records; public
-- handles, family keys, barcodes, SKUs, photo bytes/roles and stock/order IDs are unchanged.
-- Rerunnable: after the first transaction there are no shopify-* products to rename.
begin;

set local lock_timeout = '15s';
set local statement_timeout = '120s';
lock table product_family, product, product_family_photo, product_package,
    product_external_identifier, product_provenance, product_price_observation,
    catalog_import_conflict in share row exclusive mode;

create temporary table shopify_variant_renames on commit drop as
select p.id as product_id, p.familyid as family_id, f.familykey as family_key,
       p.canonicalvariantkey as old_key, 'erp-product-' || p.id as new_key
from product p left join product_family f on f.id = p.familyid
where lower(btrim(p.canonicalvariantkey)) like 'shopify-%';

-- Every legacy reference must identify the same row and family before any mutation.
-- A stale key must never silently become a shared photo or another colour's photo.
do $migration$
begin
    if exists (
        select 1 from shopify_variant_renames m join product p
          on p.canonicalvariantkey = m.new_key and p.id <> m.product_id
    ) then
        raise exception 'Shopify identity cleanup: an ERP variant key is already owned';
    end if;

    if exists (
        select 1 from product_family_photo x
        left join shopify_variant_renames m on m.old_key = x.variantexternalid
        where lower(btrim(x.variantexternalid)) like 'shopify-%'
          and (m.product_id is null or x.family_id is distinct from m.family_id
            or (x.variant_product_id is not null and x.variant_product_id <> m.product_id))
    ) then
        raise exception 'Shopify identity cleanup: unresolved or conflicting family photo';
    end if;

    if exists (
        select 1 from product_package x
        left join shopify_variant_renames m on m.old_key = x.variantexternalid
        where lower(btrim(x.variantexternalid)) like 'shopify-%'
          and (m.product_id is null or x.family_id is distinct from m.family_id
            or (x.productid is not null and x.productid <> m.product_id))
    ) then
        raise exception 'Shopify identity cleanup: unresolved or conflicting package';
    end if;

    if exists (
        select 1 from (
            select ownertype, ownerkey, productid, familyid from product_external_identifier
             where upper(btrim(source)) <> 'SHOPIFY'
            union all
            select ownertype, ownerkey, productid, familyid from product_provenance
            union all
            select ownertype, ownerkey, productid, familyid from product_price_observation
        ) x left join shopify_variant_renames m on m.old_key = x.ownerkey
        where lower(btrim(x.ownerkey)) like 'shopify-%'
          and (m.product_id is null or x.ownertype is distinct from 'VARIANT'
            or (x.productid is not null and x.productid <> m.product_id)
            or (x.familyid is not null and x.familyid is distinct from m.family_id))
    ) then
        raise exception 'Shopify identity cleanup: unresolved or conflicting historical owner';
    end if;

    if exists (
        select 1 from catalog_import_conflict x
        left join shopify_variant_renames m on m.old_key = x.canonicalvariantkey
        where lower(btrim(x.canonicalvariantkey)) like 'shopify-%'
          and (m.product_id is null or x.familykey is distinct from m.family_key)
    ) then
        raise exception 'Shopify identity cleanup: unresolved historical conflict owner';
    end if;
end
$migration$;

-- Pin old text-only links to the existing numeric ERP identity first.
update product_family_photo x set variant_product_id = m.product_id
from shopify_variant_renames m
where x.variantexternalid = m.old_key and x.variant_product_id is null;
update product_package x set productid = m.product_id
from shopify_variant_renames m
where x.variantexternalid = m.old_key and x.productid is null;

update product_family_photo x set variantexternalid = m.new_key
from shopify_variant_renames m where x.variantexternalid = m.old_key;
update product_package x set variantexternalid = m.new_key
from shopify_variant_renames m where x.variantexternalid = m.old_key;

-- Shopify's own external IDs are obsolete. Other systems' identifiers and historical
-- observations are retained, with their current owner reference kept consistent.
delete from product_external_identifier where upper(btrim(source)) = 'SHOPIFY';
update product_external_identifier x set ownerkey = m.new_key
from shopify_variant_renames m where x.ownerkey = m.old_key;
update product_provenance x set ownerkey = m.new_key
from shopify_variant_renames m where x.ownerkey = m.old_key;
update product_price_observation x set ownerkey = m.new_key
from shopify_variant_renames m where x.ownerkey = m.old_key;
update catalog_import_conflict x set canonicalvariantkey = m.new_key
from shopify_variant_renames m where x.canonicalvariantkey = m.old_key;
update product p set canonicalvariantkey = m.new_key
from shopify_variant_renames m
where p.id = m.product_id and p.canonicalvariantkey = m.old_key;

-- Stale clients or retired imports must not recreate the removed live identities.
do $migration$
begin
    if not exists (select 1 from pg_constraint
        where conrelid = 'product'::regclass and conname = 'product_canonical_variant_no_shopify') then
        alter table product add constraint product_canonical_variant_no_shopify
            check (canonicalvariantkey is null
                or lower(btrim(canonicalvariantkey)) not like 'shopify-%');
    end if;
end
$migration$;

commit;
