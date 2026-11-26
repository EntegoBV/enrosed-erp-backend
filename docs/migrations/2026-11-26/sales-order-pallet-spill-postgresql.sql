-- Spill per sales document (2026-11-26, Emre: "Je mag ook standaard maximaal 2cm spill doen. De spill willen wij ook
-- kunnen aanpassen"; 2 cm per kant, on the Deense kar across the long sides only).
-- sales_order.palletspillcm: how far an omdoos may stick out past an edge of the pallet's floor, in cm per edge, whole
-- centimetres from 0 to 5. The counts (STRICT, MIXED, PACKED) load on the floor that much larger at every edge.
-- A new document stores the standard (2) when it is made; a document made from another copies its source's value.
-- NULL = 0: every row that exists when this runs stays null and keeps counting without spill, so no sent, invoiced or
-- paid document changes a pallet or a cent. Null is never read as the standard.
--
-- Additive and rerunnable: one nullable column without a default, named as its entity names it (Hibernate's schema
-- update adds the same); no row is touched. An older backend instance never reads or writes the column: it counts a
-- document that stores a spill without it, and what it derives from such a document stores none (fix forward; the
-- documents concerned are: select id, number from sales_order where palletspillcm > 0).
-- Runs after sales-order-deense-kar-postgresql.sql (neither depends on the other).
begin;
set local lock_timeout = '15s';

alter table sales_order add column if not exists palletspillcm numeric(5,2);

commit;
