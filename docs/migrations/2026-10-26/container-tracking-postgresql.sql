-- Additive tracking storage. Purchasing status, dates, inventory and payments remain user-owned.
create table if not exists container_tracking_snapshot (
    id varchar(64) primary key,
    tracking_number varchar(80) not null,
    shipment_id bigint,
    observation_json text,
    fingerprint varchar(64),
    checked_at timestamptz,
    updated_at timestamptz,
    changed_at timestamptz,
    next_check_at timestamptz,
    last_error varchar(500),
    terminal boolean not null default false
);
create table if not exists container_tracking_lease (
    id bigint primary key,
    lease_token varchar(40),
    lease_until timestamptz
);
insert into container_tracking_lease (id) values (1) on conflict (id) do nothing;
