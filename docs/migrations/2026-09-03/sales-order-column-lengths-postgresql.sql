-- Hibernate's schema update wants sales_order.status as varchar(32) and
-- freightpricingstrategy as varchar(24), but narrows them with
-- "alter column ... varchar(n)", which PostgreSQL rejects; every start logged
-- the two failed statements. Rerunnable: altering to the same type is a no-op.
-- Enum names are far shorter than either width, so no row can be cut.

alter table sales_order alter column status type varchar(32);
alter table sales_order alter column freightpricingstrategy type varchar(24);
