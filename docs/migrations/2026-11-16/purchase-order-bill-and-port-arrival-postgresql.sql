-- Track & trace from the bill of lading (Emre, 2026-11-15: "Moet ook met bill of lading kunnen, bekijk onze
-- documenten en pas alles fatsoenlijk aan aub ook zodat alle track traces nu wel correct zijn.").
-- purchase_order.port_arrived_on: the day the linked ship arrived at the port of destination, read from its
--   port calls ("schip aangekomen", never "container gelost"); null = the ship is not in, or no ship is linked.
--   Written only by tracking; cleared when the ship or the shipment changes and when the order is no longer
--   under way.
-- purchase_order.bill_vessel / bill_voyage: the ship and voyage the final bill of lading named when it was taken
--   over; null = no final bill was taken over (a draft's ship can still change, so it is not kept). While the
--   linked ship's feed names another ship, that ship's arrival is not written.
-- All three null is how every existing order reads: nothing changes on any screen or in tracking.
--
-- Additive and rerunnable: three nullable columns, named as their entity names them. Hibernate's schema update
-- adds the same columns by itself; an older backend instance never reads or writes them. No row is written.
-- Runs after customer-login-activity-postgresql.sql.
begin;
set local lock_timeout = '15s';

alter table purchase_order add column if not exists port_arrived_on date;
alter table purchase_order add column if not exists bill_vessel varchar(80);
alter table purchase_order add column if not exists bill_voyage varchar(40);

commit;
