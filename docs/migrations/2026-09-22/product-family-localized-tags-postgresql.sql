-- Optional family tags per language. Existing family tags remain the fallback.
-- Additive and rerunnable; no product content or publication settings are changed.
begin;

alter table product_family_text add column if not exists tagsjson varchar(10000);

commit;
