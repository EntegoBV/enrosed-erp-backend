-- A product photo can open the website or the printed catalogue while the
-- first of the series stays the internal lead. Rerunnable.

alter table product_photo add column if not exists lead_roles varchar(60);
