-- Purchase receipt variance fields used to retain ordered quantity, broken pieces,
-- the line's agreed price basis and the immutable receipt-time value per piece.
--
-- Deploy before application code when DB_SCHEMA_STRATEGY=validate. Every column
-- is nullable because historical purchase lines predate receipt variance tracking.
-- This migration is additive, rerunnable and safe for the previous application.

begin;

alter table purchase_order_line
    add column if not exists orderedQuantity integer,
    add column if not exists priceBasis varchar(255),
    add column if not exists damagedQuantity integer,
    add column if not exists receiptUnitValueEur numeric(19, 4);

commit;
