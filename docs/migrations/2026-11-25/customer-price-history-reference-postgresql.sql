-- Klantprijs vanuit een factuur of offerte (Emre, 2026-11-25: "als ik een factuur maak en andere prijzen noteer,
-- ook een knop hebben om die dan zijn catalogus prijs van dat te maken zodat ook die inlog prijzen van hun aanpassen
-- meteen").
-- customer_product_price_history.reference: the number of the sales document a price was saved from (source
--   SALES_DOCUMENT, e.g. VF-2026-0031), so the customer's price history names the invoice or quote that set it.
--   Null on every row written before and on every other source (customer sheet, catalogue, deletion).
--
-- Additive and rerunnable: one nullable column, named as its entity names it (Hibernate's schema update adds the
-- same); no row is touched. An older backend instance never reads or writes the column.
-- Runs after sales-request-prices-shown-postgresql.sql (neither depends on the other).
begin;
set local lock_timeout = '15s';

alter table customer_product_price_history add column if not exists reference varchar(80);

commit;
