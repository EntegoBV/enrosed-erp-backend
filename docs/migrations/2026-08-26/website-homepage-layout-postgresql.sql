-- Typed singleton aggregate for the public homepage section order and enabled state.
-- Deploy before application code when DB_SCHEMA_STRATEGY=validate. Previous versions
-- ignore this additive table, and rerunning the migration preserves all editor data.

begin;

create table if not exists website_homepage_layout (
    id bigint primary key,
    row_revision bigint not null default 0,
    revision bigint not null default 0,
    published_revision bigint not null default 0,
    draft_sections_json varchar(4000) not null,
    published_sections_json varchar(4000) not null,
    updated_at timestamp with time zone,
    published_at timestamp with time zone,
    constraint website_homepage_layout_singleton check (id = 1)
);

insert into website_homepage_layout (
    id,
    row_revision,
    revision,
    published_revision,
    draft_sections_json,
    published_sections_json
)
values (
    1,
    0,
    0,
    0,
    '[{"key":"hero","enabled":true},{"key":"range","enabled":true},{"key":"order","enabled":true},{"key":"counter","enabled":true},{"key":"flowerbox","enabled":true},{"key":"soap","enabled":true},{"key":"occasion","enabled":false},{"key":"retail","enabled":true},{"key":"faq","enabled":true},{"key":"catalog","enabled":false},{"key":"quote","enabled":true}]',
    '[{"key":"hero","enabled":true},{"key":"range","enabled":true},{"key":"order","enabled":true},{"key":"counter","enabled":true},{"key":"flowerbox","enabled":true},{"key":"soap","enabled":true},{"key":"occasion","enabled":false},{"key":"retail","enabled":true},{"key":"faq","enabled":true},{"key":"catalog","enabled":false},{"key":"quote","enabled":true}]'
)
on conflict (id) do nothing;

commit;
