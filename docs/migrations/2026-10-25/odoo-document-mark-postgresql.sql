-- Invoice finder (2026-10-25): an incoming Odoo invoice that never becomes an ENROSED cost can be marked as
-- handled, or as belonging to a container. Read-only towards Odoo; no money, no bank link, no foreign key.
create table if not exists odoo_document_mark (
    id varchar(100) primary key,
    source_namespace varchar(64) not null,
    move_id bigint not null,
    resolution varchar(16) not null,
    purchase_order_id bigint,
    note varchar(200),
    marked_at timestamptz not null,
    marked_by varchar(100) not null,
    constraint odoo_document_mark_resolution check (resolution in ('HANDLED', 'CONTAINER')),
    constraint odoo_document_mark_container check (resolution <> 'CONTAINER' or purchase_order_id is not null)
);
create index if not exists ix_odoo_document_mark_order on odoo_document_mark (purchase_order_id);
