-- Structured activity-log change details.
--
-- Deploy before application code when DB_SCHEMA_STRATEGY=validate. The column
-- stays nullable because historical activity rows predate structured details.
-- This migration is additive, rerunnable and safe for the previous application
-- version, which ignores the new column.

begin;

alter table activity_log
    add column if not exists changes_json varchar(16000);

commit;
