-- "Verkocht per": the multiple a product's sales quantities round up to (full
-- outer carton, its packaging, or one piece). Nullable on purpose: null reads as
-- the full carton, exactly today's behaviour. A plain varchar, not an enum check.
-- Additive and rerunnable; no data is guessed here.
begin;
set local lock_timeout = '15s';

alter table product add column if not exists packagingorderstep varchar(16);

commit;
