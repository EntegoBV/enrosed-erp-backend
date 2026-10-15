-- Kostprijshistoriek: every row of product_cost_history remembers the basis its cost was applied on (sales_unit
-- PIECE or DISPLAY, the pieces in one display, what one piece is called), how it came about (kind: APPLY,
-- REAPPLY, SEED or COPY), the extra unit cost in the EXW currency and what one unit was made of in euro (goods,
-- origin, freight, duty, destination, the Enrosed kost, inspection and other costs).
-- Additive and rerunnable: twelve nullable columns, plain text instead of a check, no foreign key. The one-time
-- backfill only fills rows that have no basis or no kind yet.
-- The basis of an existing row is its product's current packaging, read the way Packaging.salesUnit() reads it
-- (per display only for a display sold per display). That is safe because the 2026-09-22 sales-unit migration
-- converted no amounts: a stored cost still holds on that basis. The first-start seed wrote applied_by 'system'
-- (a real system apply writes 'systeem'), so those rows are SEED and every other row is APPLY. The extra unit
-- cost and the parts are not backfilled: nothing reliable exists to fill them from.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

alter table product_cost_history add column if not exists kind varchar(16);
alter table product_cost_history add column if not exists sales_unit varchar(16);
alter table product_cost_history add column if not exists pieces_per_unit integer;
alter table product_cost_history add column if not exists unit_key varchar(40);
alter table product_cost_history add column if not exists extra_unit_cost numeric(19,4);
alter table product_cost_history add column if not exists goods_eur numeric(19,4);
alter table product_cost_history add column if not exists origin_eur numeric(19,4);
alter table product_cost_history add column if not exists freight_eur numeric(19,4);
alter table product_cost_history add column if not exists duty_eur numeric(19,4);
alter table product_cost_history add column if not exists destination_eur numeric(19,4);
alter table product_cost_history add column if not exists enrosed_eur numeric(19,4);
alter table product_cost_history add column if not exists separate_eur numeric(19,4);

update product_cost_history
   set (sales_unit, pieces_per_unit, unit_key) = (
       select case when p.packagingkind = 'DISPLAY' and p.packagingsalesunit = 'DISPLAY' then 'DISPLAY' else 'PIECE' end,
              case when p.packagingkind = 'DISPLAY' and p.packagingpiecesperunit > 1 then p.packagingpiecesperunit end,
              coalesce(nullif(lower(trim(p.packagingunitkey)), ''), 'stuk')
         from product p
        where p.id = product_cost_history.product_id)
 where sales_unit is null
   and exists (select 1 from product p where p.id = product_cost_history.product_id);

update product_cost_history
   set kind = case when applied_by = 'system' then 'SEED' else 'APPLY' end
 where kind is null;

COMMIT;
