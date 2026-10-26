-- Optional, explicit vessel association; original tracking reference and purchase data are never rewritten.
create table if not exists container_vessel_link (
    order_id bigint primary key,
    reference_hash varchar(64) not null,
    vessel_imo varchar(7) not null,
    linked_at timestamptz not null
);
alter table container_tracking_snapshot add column if not exists etag varchar(500);
alter table container_tracking_snapshot add column if not exists last_modified varchar(200);
