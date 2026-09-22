-- One-time short, memorable SKUs for the catalog reviewed on 22 September 2026.
-- Match product ID, family, previous SKU and canonical key together. TST has an
-- older mixed 25cm bear at ID116; its separate guarded row preserves that identity.
-- Product IDs, barcodes, public handles, images, stock and order relations stay intact.
begin;
set local lock_timeout = '15s';
set local statement_timeout = '120s';
lock table product_family, product in share row exclusive mode;
create table if not exists catalog_data_patch (
    patch_key varchar(120) primary key,
    applied_at timestamptz not null default now(),
    affected_rows integer not null,
    before_state jsonb not null
);
create temporary table short_sku_plan (
    product_id bigint, family_key varchar(255), old_sku varchar(255),
    new_sku varchar(255), old_canonical_key varchar(255)
) on commit drop;
insert into short_sku_plan values
    (45, 'rose-diamonds-within-display', 'ENR-ROSE-DIAMONDS-WITHIN-DISPLAY-RED', 'DIAM-D-RD', 'shopify-46685757931689'),
    (46, 'rose-diamonds-within-display', 'ENR-ROSE-DIAMONDS-WITHIN-DISPLAY-CHERRY-PINK', 'DIAM-D-CP', 'shopify-46685588127913'),
    (47, 'rose-diamonds-within-display', 'ENR-ROSE-DIAMONDS-WITHIN-DISPLAY-NAVY', 'DIAM-D-NV', 'shopify-46685588095145'),
    (48, 'rose-diamonds-within-display', 'ENR-ROSE-DIAMONDS-WITHIN-DISPLAY-WHITE', 'DIAM-D-WH', 'shopify-46685588160681'),
    (49, 'preserved-single-rose-in-display', 'ENR-PRESERVED-SINGLE-ROSE-IN-DISPLAY-RED', 'STEM-SLV-RD', 'shopify-46736418275497'),
    (50, 'preserved-single-rose-in-display', 'ENR-PRESERVED-SINGLE-ROSE-IN-DISPLAY-PINK', 'STEM-SLV-PK', 'shopify-46736420765865'),
    (51, 'preserved-single-rose-in-display', 'ENR-PRESERVED-SINGLE-ROSE-IN-DISPLAY-BLUE', 'STEM-SLV-NV', 'shopify-46736420798633'),
    (52, 'preserved-single-rose-in-display', 'ENR-PRESERVED-SINGLE-ROSE-IN-DISPLAY-WHITE', 'STEM-SLV-WH', 'shopify-46736420831401'),
    (53, 'preserved-bowl-rose', 'ENR-PRESERVED-BOWL-ROSE-RED', 'BOWL-M-RD', 'shopify-46736421650601'),
    (54, 'preserved-bowl-rose', 'ENR-PRESERVED-BOWL-ROSE-PINK', 'BOWL-M-PK', 'shopify-46736421683369'),
    (55, 'preserved-bowl-rose', 'ENR-PRESERVED-BOWL-ROSE-WHITE', 'BOWL-M-WH', 'shopify-46736421716137'),
    (56, 'preserved-bowl-rose', 'ENR-PRESERVED-BOWL-ROSE-BLUE', 'BOWL-M-NV', 'shopify-46736421748905'),
    (57, 'bowl-rose-xl', 'ENR-BOWL-ROSE-XL-RED', 'BOWL-XL-RD', 'shopify-46736106684585'),
    (58, 'bowl-rose-xl', 'ENR-BOWL-ROSE-XL-CHERRY-PINK', 'BOWL-XL-CP', 'shopify-46736106717353'),
    (59, 'bowl-rose-xl', 'ENR-BOWL-ROSE-XL-NAVY', 'BOWL-XL-NV', 'shopify-46736106750121'),
    (60, 'bowl-rose-xl', 'ENR-BOWL-ROSE-XL-WHITE', 'BOWL-XL-WH', 'shopify-46736106782889'),
    (61, 'cobalt-blue-roos-in-glazen-stolp', 'ENR-COBALT-BLUE-ROOS-IN-GLAZEN-STOLP-RED', 'DOM-12X25-RD', 'shopify-44784498147497'),
    (62, 'cobalt-blue-roos-in-glazen-stolp', 'ENR-COBALT-BLUE-ROOS-IN-GLAZEN-STOLP-CHERRY-PINK', 'DOM-12X25-CP', 'shopify-44784500277417'),
    (63, 'cobalt-blue-roos-in-glazen-stolp', 'ENR-COBALT-BLUE-ROOS-IN-GLAZEN-STOLP-NAVY', 'DOM-12X25-NV', 'shopify-44784500244649'),
    (64, 'cobalt-blue-roos-in-glazen-stolp', 'ENR-COBALT-BLUE-ROOS-IN-GLAZEN-STOLP-WHITE', 'DOM-12X25-WH', 'shopify-44784500310185'),
    (65, 'single-rose-in-acryl-glass-box', 'ENR-SINGLE-ROSE-IN-ACRYL-GLASS-BOX-RED', 'MIR-RD', 'shopify-44784485400745'),
    (66, 'one-rose-in-box', 'ENR-ONE-ROSE-IN-BOX-NAVY', 'ACR-NV', 'shopify-44784495526057'),
    (67, 'one-rose-in-box', 'ENR-ONE-ROSE-IN-BOX-RED', 'ACR-RD', 'shopify-44784495558825'),
    (68, 'one-rose-in-box', 'ENR-ONE-ROSE-IN-BOX-CHERRY-PINK', 'ACR-CP', 'shopify-44784495591593'),
    (69, 'hearth-glass-flowerbox', 'ENR-HEARTH-GLASS-FLOWERBOX-RED', 'HRT16-RD', 'shopify-44784466362537'),
    (70, 'roses-in-box-16pcs', 'ENR-ROSES-IN-BOX-16PCS-RED', 'BOX16-RD', 'shopify-44784490840233'),
    (71, 'roses-in-box-16pcs', 'ENR-ROSES-IN-BOX-16PCS-LIGHT-BLUE', 'BOX16-LB', 'shopify-44784490873001'),
    (72, 'roses-in-box-16pcs', 'ENR-ROSES-IN-BOX-16PCS-CHERRY-PINK', 'BOX16-CP', 'shopify-44784490905769'),
    (73, 'rose-in-dome-elite', 'ENR-ROSE-IN-DOME-ELITE-RED', 'DOM-15X30-3R-RD', 'shopify-44836583407785'),
    (74, 'roses-in-box-9pcs', 'ENR-ROSES-IN-BOX-9PCS-RED', 'BOX9-RD', 'shopify-44784491364521'),
    (75, 'roses-in-box-9pcs', 'ENR-ROSES-IN-BOX-9PCS-LIGHT-BLUE', 'BOX9-LB', 'shopify-44784491397289'),
    (76, 'roses-in-box-9pcs', 'ENR-ROSES-IN-BOX-9PCS-CHERRY-PINK', 'BOX9-CP', 'shopify-44784491430057'),
    (77, 'acrylic-flowerbox', 'ENR-ACRYLIC-FLOWERBOX-RED', 'CUBE-RD', 'shopify-44784460824745'),
    (81, 'glass-flowerbox', 'ENR-GLASS-FLOWERBOX-RED', 'FRAME16-RD', 'shopify-44784461775017'),
    (82, 'rose-in-dome-xl', 'ENR-ROSE-IN-DOME-XL-RED', 'DOM-12X20-RD', 'shopify-44887957274793'),
    (83, 'rose-in-dome-xl', 'ENR-ROSE-IN-DOME-XL-NAVY', 'DOM-12X20-NV', 'shopify-44887957340329'),
    (84, 'rose-in-dome-xl', 'ENR-ROSE-IN-DOME-XL-CHERRY-PINK', 'DOM-12X20-CP', 'shopify-44887957373097'),
    (85, 'rose-in-dome-xl', 'ENR-ROSE-IN-DOME-XL-WHITE', 'DOM-12X20-WH', 'shopify-44887957307561'),
    (86, 'diamond-rose', 'ENR-DIAMOND-ROSE-RED', 'DIAM-XL-RD', 'shopify-44784487399593'),
    (87, 'soaproos-in-vensterdoos', 'ENR-SOAPROOS-IN-VENSTERDOOS-DEFAULT', 'SOAP-WIN-RD', 'shopify-46736427745449'),
    (88, 'soap-rose-box-led', 'ENR-SOAP-ROSE-BOX-LED-RED', 'SOAP-LED-RD', 'shopify-44784482320553'),
    (89, 'soap-rose-box-led', 'ENR-SOAP-ROSE-BOX-LED-WHITE', 'SOAP-LED-WH', 'shopify-46685592912041'),
    (90, 'soap-rose-box-led', 'ENR-SOAP-ROSE-BOX-LED-CHERRY-PINK', 'SOAP-LED-CP', 'shopify-46685592944809'),
    (91, 'soap-roos-in-box', 'ENR-SOAP-ROOS-IN-BOX-DEFAULT', 'SOAP-BOX-RD', 'shopify-46736426041513'),
    (94, 'odoo-dome-15x30-single-review', 'ENR-ODOO-DOME-15X30-SINGLE-REVIEW-BLUE', 'DOM-15X30-BL', 'odoo-rose-in-dome-15x30cm-blue'),
    (95, 'odoo-dome-15x30-single-review', 'ENR-ODOO-DOME-15X30-SINGLE-REVIEW-RED', 'DOM-15X30-RD', 'odoo-rose-in-dome-15x30cm-red'),
    (97, 'odoo-dome-15x30-single-review', 'ENR-ODOO-DOME-15X30-SINGLE-REVIEW-WHITE', 'DOM-15X30-WH', 'odoo-rose-in-dome-15x30cm-white'),
    (98, 'foam-half-heart-25', 'ENR-ODOO-HALF-HEART-FOAM-25-PINK', 'FHH-25-PK', 'foam-half-heart-25-pink'),
    (99, 'foam-half-heart-25', 'ENR-ODOO-HALF-HEART-FOAM-25-RED', 'FHH-25-RD', 'foam-half-heart-25-red'),
    (100, 'foam-half-heart-40', 'ENR-ODOO-HALF-HEART-FOAM-40-PINK', 'FHH-40-PK', 'foam-half-heart-40-pink'),
    (101, 'foam-half-heart-40', 'ENR-ODOO-HALF-HEART-FOAM-40-RED', 'FHH-40-RD', 'foam-half-heart-40-red'),
    (102, 'odoo-preserved-rose-windowbox', 'ENR-ODOO-PRESERVED-ROSE-WINDOWBOX-PRESERVED-ROSE-IN-WINDOWBOX', 'ROSE-WIN-RD', 'odoo-preserved-rose-in-windowbox'),
    (103, 'long-stem-rose-box-display', 'ENR-P01', 'STEM-BOX-RD', 'long-stem-rose-box-display-red'),
    (104, 'long-stem-rose-box-display', 'ENR-P02', 'STEM-BOX-PK', 'long-stem-rose-box-display-pink'),
    (105, 'long-stem-rose-box-display', 'ENR-P03', 'STEM-BOX-WH', 'long-stem-rose-box-display-white'),
    (106, 'long-stem-rose-box-display', 'ENR-P04', 'STEM-BOX-NV', 'long-stem-rose-box-display-navy'),
    (107, 'foam-bear-with-heart-25', 'ENR-P05', 'FBH-25-RD', 'foam-bear-with-heart-25-red'),
    (108, 'foam-bear-25', 'ENR-P06', 'FB-25-RD', 'foam-bear-25-red'),
    (109, 'foam-bear-25', 'ENR-P07', 'FB-25-PK', 'foam-bear-25-pink'),
    (110, 'odoo-dome-15x30-single-review', 'ENR-P08', 'DOM-15X30-PK', null),
    (111, 'foam-heart-15', 'ENR-P09', 'FH-15-RD', 'foam-heart-15-red'),
    (112, 'foam-heart-15', 'ENR-P10', 'FH-15-PK', 'foam-heart-15-pink'),
    (113, 'model-113-114', 'ENR-P11', 'DEMO-FU', null),
    (114, 'model-113-114', 'ENR-P12', 'DEMO-CH', null),
    (115, 'model-113-114', 'ENR-P13', 'DEMO-LI', null),
    (116, 'model-116-117', 'ENR-P14', 'FBH-40-RD', null),
    (117, 'model-116-117', 'ENR-P15', 'FBH-40-PD', null),
    (118, 'foam-bear-25', 'ENR-P16', 'FB-25-MX', 'foam-bear-25-mixed'),
    (119, 'model-119-120', 'ENR-P17', 'DOM-12X25-NB-RD', null),
    (120, 'model-119-120', 'ENR-P18', 'DOM-12X25-NB-PK', null),
    (121, 'model-119-120', 'ENR-P19', 'DOM-12X25-NB-WH', null),
    (122, 'model-119-120', 'ENR-P20', 'DOM-12X25-NB-BL', null),
    (123, 'rose-in-dome-7x23', 'ENR-P21', 'DOM-7X23-RD', 'rose-in-dome-7x23-red'),
    (124, 'rose-in-dome-7x23', 'ENR-P22', 'DOM-7X23-PK', 'rose-in-dome-7x23-pink'),
    (125, 'rose-in-dome-7x23', 'ENR-P23', 'DOM-7X23-WH', 'rose-in-dome-7x23-white'),
    (126, 'rose-in-dome-7x23', 'ENR-P24', 'DOM-7X23-BL', 'rose-in-dome-7x23-blue'),
    (116, 'foam-bear-25', 'ENR-P14', 'FB-25-MX', 'foam-bear-25-mixed');
create temporary table short_sku_changes on commit drop as
select p.id as product_id, p.sku as old_sku, m.new_sku
from product p
join product_family f on f.id = p.familyid
join short_sku_plan m on m.product_id = p.id and m.family_key = f.familykey
where p.sku = m.old_sku
  and (p.canonicalvariantkey is not distinct from m.old_canonical_key
    or (m.old_canonical_key like 'shopify-%' and p.canonicalvariantkey = 'erp-product-' || p.id));
do $migration$
declare changes integer;
begin
    if exists (select 1 from catalog_data_patch where patch_key = 'short-skus-2026-09-22') then
        return;
    end if;
    if exists (
        select 1 from product p join product_family f on f.id = p.familyid
        where exists (select 1 from short_sku_plan m where m.product_id = p.id)
          and not exists (
            select 1 from short_sku_plan m
            where m.product_id = p.id and m.family_key = f.familykey
              and p.sku in (m.old_sku, m.new_sku)
              and (p.canonicalvariantkey is not distinct from m.old_canonical_key
                or (m.old_canonical_key like 'shopify-%'
                  and p.canonicalvariantkey = 'erp-product-' || p.id))
          )
    ) then
        raise exception 'Short SKU migration: catalog identity changed since review; refusing a partial update';
    end if;
    select count(*) into changes from short_sku_changes;
    if changes = 0 then return; end if;
    if exists (select 1 from short_sku_changes group by product_id having count(*) > 1)
       or exists (select 1 from short_sku_changes group by new_sku having count(*) > 1) then
        raise exception 'Short SKU migration: ambiguous product or duplicate target SKU';
    end if;
    if exists (select 1 from short_sku_changes c join product p
        on p.sku = c.new_sku and p.id <> c.product_id) then
        raise exception 'Short SKU migration: target SKU is already in use';
    end if;
    insert into catalog_data_patch(patch_key, affected_rows, before_state)
    select 'short-skus-2026-09-22', changes, jsonb_agg(to_jsonb(c) order by c.product_id)
    from short_sku_changes c;
    update product p set sku = c.new_sku from short_sku_changes c where p.id = c.product_id;
    raise notice 'Short SKU migration: % product codes updated', changes;
end
$migration$;
commit;
