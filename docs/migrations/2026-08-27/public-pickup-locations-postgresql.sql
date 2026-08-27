-- Customer-selectable collection points backed by existing stock locations.
--
-- Deploy before application code when DB_SCHEMA_STRATEGY=validate. Previous
-- versions ignore these additive columns. No location is exposed automatically:
-- an administrator must deliberately enable it and provide public facts first.

begin;

alter table stock_location
    add column if not exists public_pickup_point boolean not null default false;

alter table stock_location
    add column if not exists public_pickup_label varchar(255);

alter table stock_location
    add column if not exists public_pickup_address varchar(500);

alter table stock_location
    add column if not exists public_pickup_instructions varchar(2000);

alter table stock_location
    add column if not exists public_pickup_position integer not null default 0;

-- Snapshot columns are intentionally denormalised. A submitted website request
-- must retain the label, address and instructions selected by the buyer even
-- when the stock location is edited or disabled afterwards.
alter table sales_order
    add column if not exists pickup_location_id bigint;

alter table sales_order
    add column if not exists pickup_location_label varchar(255);

alter table sales_order
    add column if not exists pickup_location_address varchar(500);

alter table sales_order
    add column if not exists pickup_location_instructions varchar(2000);

commit;
