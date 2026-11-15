-- Aankomst met de hand (Emre, 2026-11-15: "aankomst container ook manueel kunnen zetten en aankomst
-- Zaltbommel manueel op dag en uur, Verhoeven moet het op zijn portaal terugzien").
-- purchase_order.expected_arrival_time: the hour the container is expected at the port of arrival, set by
--   hand next to expected_arrival; null = no hour. A date tracking or the order form moves clears it.
-- purchase_order.arrival_by_hand: expected_arrival was set by hand while the order had track & trace, so
--   tracking no longer moves it ("Volg track & trace" hands it back); null reads as no.
-- purchase_order.warehouse_arrival_on / warehouse_arrival_time: when the container is expected at the place it
--   is unloaded at ("Lossen op", Zaltbommel for the main warehouse), day and hour, set by hand; null = not planned.
-- All four null is how every existing order reads: nothing changes on any screen or in tracking.
--
-- Additive and rerunnable: four nullable columns, named as their entity names them. Hibernate's schema update
-- adds the same columns by itself; an older backend instance never reads or writes them. No row is written.
-- Runs after stock-radar-removed-postgresql.sql.
begin;
set local lock_timeout = '15s';

alter table purchase_order add column if not exists expected_arrival_time time;
alter table purchase_order add column if not exists arrival_by_hand boolean;
alter table purchase_order add column if not exists warehouse_arrival_on date;
alter table purchase_order add column if not exists warehouse_arrival_time time;

commit;
