-- Verkoopofferte: aantallen per stuk (Emre, 2026-11-10): staff can choose per line of a sales
-- document that its quantity counts per piece, so it is not rounded up to the product's order step
-- (omdoos or display). TRUE = per piece on this line. Null is every line written before and every
-- line that rounds as its product says, which is exactly today's behaviour: no row is touched and
-- nothing is guessed. FALSE is never written. Nullable, no default, and named as the entity names
-- it (perpiece). Hibernate adds the column by itself where the schema strategy is "update"; this
-- file is for an environment that validates, and for the record.
-- Additive and rerunnable; runs after 2026-11-10/purchase-order-arrival-moved-postgresql.sql (neither depends on the other).
begin;
set local lock_timeout = '15s';

alter table sales_order_line add column if not exists perpiece boolean;

commit;
