-- Track & trace: the arrival tracking moved (Emre, 2026-11-10: "Bekijk ook of de track trace beter kan").
-- purchase_order.arrival_moved_from: the expected arrival that stood before tracking first replaced it.
-- purchase_order.arrival_moved_at: when tracking last moved the arrival.
-- purchase_order.arrival_notice_date: the arrival date the bell last named (two days or more from the one before).
-- purchase_order.arrival_notice_at: when it named it, the bell row's time and the start of its 14 days.
-- All four null = tracking did not move the arrival, which is how every existing order reads: the
-- screens show nothing and the bell says nothing. Written only by TrackingDateSync; a date saved
-- through the order clears all four. No row is written here.
--
-- Additive and rerunnable: four nullable columns, named as their entity names them. Hibernate's
-- schema update adds the same columns by itself; an older backend instance never reads or writes
-- them. Runs after customer-crm-status-touchpoints-postgresql.sql.
begin;
set local lock_timeout = '15s';

alter table purchase_order add column if not exists arrival_moved_from date;
alter table purchase_order add column if not exists arrival_moved_at timestamptz;
alter table purchase_order add column if not exists arrival_notice_date date;
alter table purchase_order add column if not exists arrival_notice_at timestamptz;

commit;
