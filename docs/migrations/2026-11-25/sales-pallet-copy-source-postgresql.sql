-- Hand pallets remember what Zelf indelen copied (2026-11-25, follow-up of the PACKED release: a hand pallet that fits
-- must never read "te hoog" and block Versturen, whatever edits follow the copy).
-- sales_pallet.copy_source: written by the ERP when Zelf indelen copies a PACKED document's automatic pallets, e.g.
--   "PACKED|EURO_120X80|260|12:24,15:8|0|12:20,15:8" (the rule, the pallet profile, the loaded height, the run's
--   cartons per product in line order, the pallet's place in the run, its own cartons). The ERP loads that run again
--   to judge the pallet after later edits; the server only keeps it (OrderPallet.source: at most 4000 characters of
--   the format's own, else null). Null on every pallet built by hand, on every pallet stored before and on other
--   rules.
--
-- Additive and rerunnable: one nullable column, named as its entity names it (Hibernate's schema update adds the
-- same); no row is touched. An older backend instance never reads or writes the column; an older ERP keeps the field
-- on the pallets it loads and sends none on pallets it makes.
-- Runs after customer-price-history-reference-postgresql.sql (neither depends on the other).
begin;
set local lock_timeout = '15s';

alter table sales_pallet add column if not exists copy_source varchar(4000);

commit;
