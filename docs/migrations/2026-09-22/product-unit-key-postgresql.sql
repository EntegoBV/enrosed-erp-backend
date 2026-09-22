-- What one piece of a product is called on customer documents ("per bowl").
-- Nullable on purpose: null reads as the default unit "stuk" in code, so every
-- existing product keeps its current wording. Additive and rerunnable; the owner
-- chooses units per product in the ERP, no data is guessed here.
begin;
set local lock_timeout = '15s';

alter table product add column if not exists packagingunitkey varchar(40);

commit;
