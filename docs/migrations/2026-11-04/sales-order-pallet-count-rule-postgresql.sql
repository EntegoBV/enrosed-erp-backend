-- How a sales document counts its automatic pallets (Emre, 2026-11-04: "Ja, gemengd tellen").
-- STRICT puts every product on pallets of its own, the count every document had until now; MIXED
-- lets products share a pallet in whole layers, as Palletberekening does. The application writes the
-- rule once, when it makes a document (new ones MIXED, an invoice, advance, credit note or split part
-- its source's), and no save changes it: the column is not updatable in the entity.
--
-- Rerunnable, and the backfill runs once: only in the run that creates the column. Concept quotes (no
-- document type or OFFERTE, status CONCEPT, not deleted, never sent, not a website order) count mixed
-- from then on; every other row (any other status, a reopened quote, a website order, invoices, credit
-- notes, deleted rows) keeps the strict count and freight it has. A later run changes nothing, so a
-- document that left concept after the first run is never re-classified.
-- deleted_at comes from 2026-09-10/deleted-items-postgresql.sql, which runs before this file.
--
-- When this file is not run before the release (a forgotten listing), Hibernate adds the column
-- without values and every older row keeps counting strict: no document changes its count or freight,
-- only the concept quotes of that moment stay strict. A run after that finds the column and changes
-- nothing either. A plain varchar without a CHECK constraint: an unknown value also reads as STRICT.
begin;
set local lock_timeout = '15s';
set local statement_timeout = '60s';

do $migration$
declare
    target_table regclass := to_regclass('sales_order');
begin
    /* A fresh database: Hibernate creates the table, with the column, after this. */
    if target_table is null then return; end if;
    if exists (select 1 from pg_attribute
               where attrelid = target_table and attname = 'palletcountrule' and not attisdropped) then
        return;
    end if;
    alter table sales_order add column palletcountrule varchar(16);
    /* A concept that was ever sent (reopened, or a proposal taken over) keeps the count the customer
       saw, and so does a website order: its ordered terms carry the strict freight. */
    update sales_order o set palletcountrule = case
        when (o.doctype is null or o.doctype = 'OFFERTE') and o.status = 'CONCEPT' and o.deleted_at is null
             and o.sentat is null
             and not exists (select 1 from sales_web_order w where w.sales_order_id = o.id)
            then 'MIXED'
        else 'STRICT'
    end;
end
$migration$;

commit;
