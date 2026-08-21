-- Run once before deploying the Category @Version contract to an existing PostgreSQL database.
-- The separate nullable/add, backfill and constraint steps are safe when category already has rows.
begin;

alter table category add column if not exists revision bigint;
update category set revision = 0 where revision is null;
alter table category alter column revision set default 0;
alter table category alter column revision set not null;
alter table category alter column description type varchar(4000);

commit;
