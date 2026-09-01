-- Internal per-product supplier note used in purchasing and supplier PDF exports.
--
-- Deploy before application code when DB_SCHEMA_STRATEGY=validate. The column
-- is nullable because existing products do not yet have a supplier agreement note.
-- This migration is additive, rerunnable and safe for the previous application,
-- which ignores the new column.

begin;

alter table product
    add column if not exists supplierNote varchar(4000);

commit;
