-- Track & trace: the arrival tracking moved (Emre, 2026-11-10: "Bekijk ook of de track trace beter kan").
-- purchase_order.arrival_moved_from: the expected arrival that stood before tracking first replaced it.
-- purchase_order.arrival_moved_at: when tracking last moved the arrival.
-- purchase_order.arrival_notice_date: the arrival date the bell's "Aankomst … later/eerder" notice names.
-- All three null = tracking did not move the arrival, which is how every existing order reads: the
-- screens show nothing and the bell says nothing. Written only by TrackingDateSync; a date saved
-- through the order clears all three. No row is written here.
--
-- Additive and rerunnable: three nullable columns, named as their entity names them. Hibernate's
-- schema update adds the same columns by itself; an older backend instance never reads or writes
-- them. Runs after customer-crm-status-touchpoints-postgresql.sql.
begin;
set local lock_timeout = '15s';

alter table purchase_order add column if not exists arrival_moved_from date;
alter table purchase_order add column if not exists arrival_moved_at timestamptz;
alter table purchase_order add column if not exists arrival_notice_date date;

commit;
