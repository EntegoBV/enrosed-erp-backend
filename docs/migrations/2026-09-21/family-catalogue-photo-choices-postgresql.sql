-- Independent print choices use persisted ERP media IDs; null keeps automatic selection.
-- Additive and rerunnable: never replace administrator selections or stored photos.
begin;

alter table product_family add column if not exists catalogueoverviewphotoid bigint;
alter table product_family add column if not exists cataloguedetailphotoid bigint;
alter table product_family add column if not exists cataloguedetailsize varchar(16);

commit;
