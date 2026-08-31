-- One-time marker table for the country defaults seed.
--
-- Apply before deploying application code when DB_SCHEMA_STRATEGY=validate.
-- The table is additive, rerunnable, and ignored by earlier application versions.

begin;

create table if not exists country_policy_version (
    version varchar(80) not null,
    appliedat timestamp(6) with time zone not null,
    constraint country_policy_version_pkey primary key (version)
);

commit;
