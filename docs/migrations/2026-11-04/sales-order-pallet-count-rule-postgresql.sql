-- How a sales document counts its automatic pallets (Emre, 2026-11-04: "Ja, gemengd tellen").
-- STRICT puts every product on pallets of its own, the count every document had until now; MIXED
-- lets products share a pallet in whole layers, as Palletberekening does. The application writes the
-- rule once, when it makes a document (new ones MIXED, an invoice, advance, credit note or split part
-- its source's), and no save changes it: the column is not updatable in the entity.
--
-- Rerunnable, and it fills rows only in the run that creates the column, and only with STRICT: every
-- row keeps the strict count and freight it has, except a concept quote nothing has come from yet (no
-- document type or OFFERTE, status CONCEPT, not deleted, never sent, no website order, no advance
-- invoice, no invoice or other document made from it, no delivery split, no partner advance snapshot).
-- Those rows stay empty, and the new release decides them when it starts (PalletCountRuleBackfill,
-- the same test word for word): MIXED while still such a concept quote, else STRICT. The old release
-- keeps serving after this file ran, until the cut-over, or longer when the new deployment fails: a
-- quote it sends in that window, and every document it makes, is decided at the new release's start
-- by what it is then, so a quote sent with the strict count keeps it. Until decided, a row without a
-- rule reads STRICT. A later run changes nothing.
-- deleted_at comes from 2026-09-10/deleted-items-postgresql.sql, the other tables from
-- advance-quote-arrangements, sales-splits, sales-advance-billing and sales-web-order: all run before
-- this file.
--
-- When this file is not run before the release (a forgotten listing), Hibernate adds the column
-- without values and the release's start decides every row as above; a run after that finds the
-- column and changes nothing. A plain varchar without a CHECK constraint: an unknown value also reads
-- as STRICT.
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
       saw; a website order the freight of its ordered terms; a quote with an advance, an invoice, a
       split or a partner advance the count those were made on. */
    update sales_order o set palletcountrule = case
        when (o.doctype is null or o.doctype = 'OFFERTE') and o.status = 'CONCEPT'
             and o.deleted_at is null and o.sentat is null
             and not exists (select 1 from sales_web_order w where w.sales_order_id = o.id)
             and not exists (select 1 from sales_advance_billing b where b.quote_id = o.id)
             and not exists (select 1 from sales_order i where i.sourcequoteid = o.id)
             and not exists (select 1 from sales_split_group g where g.root_order_id = o.id or g.later_order_id = o.id)
             and not exists (select 1 from partner_advance_quote_snapshot p where p.sales_order_id = o.id)
            then null
        else 'STRICT'
    end;
end
$migration$;

commit;
