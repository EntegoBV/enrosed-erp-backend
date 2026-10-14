-- "Verkocht per": the multiple a product's sales quantities round up to (full
-- outer carton, its packaging, or one piece), and on every sales line the step it
-- was last rounded to, so a later change on the product never re-prices a sent or
-- paid document. Both nullable on purpose: null reads as the full carton on the
-- product and as "the product's step" on a line, which is exactly today's
-- behaviour. A plain varchar, not an enum check. Additive and rerunnable; no data
-- is guessed here.
begin;
set local lock_timeout = '15s';

alter table product add column if not exists packagingorderstep varchar(16);
alter table sales_order_line add column if not exists unitsperorderstep integer;

commit;
