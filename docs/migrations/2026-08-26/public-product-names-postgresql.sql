-- Add independently editable public product names without changing the established
-- quote/order/invoice meaning of product.name and product_text.name.
--
-- Deploy this before code when DB_SCHEMA_STRATEGY=validate. It is safe to run
-- repeatedly and safe for the previous application version, which ignores both columns.

begin;

alter table product
    add column if not exists public_name varchar(255);

alter table product_text
    add column if not exists public_name varchar(255);

-- Preserve the exact current public projection at rollout. Runtime copy-on-write
-- inheritance keeps these values following document names until an editor deliberately
-- gives the public name a different value.
update product
set public_name = name
where nullif(btrim(public_name), '') is null
  and nullif(btrim(name), '') is not null;

update product_text
set public_name = name
where nullif(btrim(public_name), '') is null
  and nullif(btrim(name), '') is not null;

commit;
