-- Immutable purchase-order creator metadata.
--
-- Deploy before application code when DB_SCHEMA_STRATEGY=validate. Columns stay
-- nullable because historical orders predate named staff accounts and must not
-- be attributed to Emre or Berat without evidence.

begin;

alter table purchase_order
    add column if not exists created_by varchar(64);

alter table purchase_order
    add column if not exists created_by_display_name varchar(120);

alter table purchase_order
    add column if not exists created_at timestamp with time zone;

commit;
