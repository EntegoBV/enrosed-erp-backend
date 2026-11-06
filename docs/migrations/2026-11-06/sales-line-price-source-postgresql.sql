-- Klantprijzen on quotes and invoices (Emre, 2026-11-06): where a sales line's unit price comes
-- from. CUSTOMER = the customer's agreed price, pinned on the line when it was added (or when a
-- concept changed customer); STANDARD = staff chose the catalogue price although the customer has
-- one; MANUAL = typed. Null is every line written before and a line that follows the catalogue by
-- default, which is exactly today's behaviour: no row is touched and nothing is guessed. Nullable,
-- a plain varchar without a CHECK constraint, and named as the entity names it (pricesource).
-- Additive and rerunnable; runs after customer-prices-and-catalog-exports-postgresql.sql.
begin;
set local lock_timeout = '15s';

alter table sales_order_line add column if not exists pricesource varchar(16);

commit;
