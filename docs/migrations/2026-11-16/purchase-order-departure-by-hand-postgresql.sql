-- Vertrek met de hand (Emre, 2026-11-16, asked "Zal ik een vertrekdag met de hand toevoegen aan het
-- Aankomst-venster?": "Ja, voeg toe").
-- purchase_order.departure_by_hand: shipped_on was set by hand in the Aankomst sheet, so tracking no longer
--   moves it (a source that names another day within the voyage window would put its own day back at every
--   pass); null reads as no. It ends when the day is written through an order save: a status step back, or a
--   bill of lading taken over with its on-board day.
-- Null is how every existing order reads: nothing changes on any screen or in tracking.
--
-- Additive and rerunnable: one nullable column, named as its entity names it. Hibernate's schema update adds the
-- same column by itself; an older backend instance never reads or writes it. No row is written.
-- Runs after purchase-order-bill-and-port-arrival-postgresql.sql.
begin;
set local lock_timeout = '15s';

alter table purchase_order add column if not exists departure_by_hand boolean;

commit;
