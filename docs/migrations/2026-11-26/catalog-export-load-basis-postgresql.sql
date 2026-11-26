-- Loading heights and spill per catalogue export (2026-11-26, Emre: "Catalogus pdf zowel korte en lange daar moet ik
-- ook eigenlijk kunnen aanpassen op pallet en deense car de hoogte zodat dan de aantallen kunnen veranderen ... want
-- sommige klanten hebben andere standaard hoogtes en die willen dan een andere overzicht van ons. ... De spill willen
-- wij ook kunnen aanpassen").
-- catalog_export.pallet_height_cm, kar_height_cm, spill_cm: what the PDF's per-pallet and per-kar figures were counted
-- with, in cm: the europallet's loaded height with the wood, the Deense kar's from the floor, the spill per edge (on
-- the kar across the long sides only). Always the values used, the standard of that day included (260 / 230 / 2
-- today), so a journal row says which figures the customer saw even after a standard moves.
-- NULL = a PDF made before this release: every row that exists when this runs stays null.
--
-- Additive and rerunnable: three nullable columns without a default, named as the entity names them (Hibernate's
-- schema update adds the same); no row is touched. An older backend instance never reads or writes the columns: a PDF
-- it makes is journaled with nulls (and counts on its own standard, whatever the request asked).
-- Runs after sales-order-pallet-spill-postgresql.sql (neither depends on the other).
begin;
set local lock_timeout = '15s';

alter table catalog_export add column if not exists pallet_height_cm numeric(6,1);
alter table catalog_export add column if not exists kar_height_cm numeric(6,1);
alter table catalog_export add column if not exists spill_cm numeric(4,1);

commit;
