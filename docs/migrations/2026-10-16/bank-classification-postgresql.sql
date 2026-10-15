-- "Boeken als": a bank transaction without an invoice or cost (an internal transfer, VAT, a loan, capital,
-- private money, something else) is booked as such and leaves "Te koppelen". An internal transfer names its
-- other leg; a VAT payment names its quarter ('YYYY-Qn'). The classification is plain text so a later value
-- needs no migration.
-- purchase_payment_bank_adjustment remembers what a purchase payment was before it first followed its bank
-- transaction ("aangepast aan de bank (was ...)"); a hand edit of the amount or date, or deleting the
-- payment, removes the row.
-- Additive and rerunnable: nullable columns, one index, one new table. No foreign key, no backfill.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

alter table bank_statement_line add column if not exists classification varchar(24);
alter table bank_statement_line add column if not exists classification_note varchar(300);
alter table bank_statement_line add column if not exists transfer_line_id bigint;
alter table bank_statement_line add column if not exists vat_period varchar(7);
alter table bank_statement_line add column if not exists classified_at timestamp(6) with time zone;
alter table bank_statement_line add column if not exists classified_by varchar(255);
create index if not exists ix_bank_statement_line_transfer on bank_statement_line(transfer_line_id);
create table if not exists purchase_payment_bank_adjustment (
    payment_id bigint primary key,
    from_amount_eur numeric(19,2) not null,
    from_paid_on date not null,
    bank_statement_line_id bigint,
    adjusted_at timestamp(6) with time zone not null,
    actor varchar(255)
);

COMMIT;
