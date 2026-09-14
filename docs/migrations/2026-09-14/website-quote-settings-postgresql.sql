-- Public quote price visibility is an independent, persisted presentation setting.
-- Rerunning this migration preserves the administrator's current choice.
begin;

create table if not exists website_quote_settings (
    id bigint primary key,
    row_revision bigint not null default 0,
    prices_visible boolean not null default true,
    constraint website_quote_settings_singleton check (id = 1)
);

insert into website_quote_settings (id, row_revision, prices_visible)
values (1, 0, true)
on conflict (id) do nothing;

commit;
