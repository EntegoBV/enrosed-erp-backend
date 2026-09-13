-- Explicit private supplier-agreement applicability. Existing product content stays untouched.
begin;
create table if not exists product_supplier_agreement_link (
    product_id bigint primary key,
    source_product_id bigint not null,
    supplier_id bigint not null,
    family_id bigint not null,
    updated_at timestamp with time zone not null,
    constraint ck_supplier_agreement_not_self check (product_id <> source_product_id)
);
do $migration$
begin
    if to_regclass('product') is not null then
        if not exists (select 1 from pg_constraint where conname = 'fk_supplier_agreement_target'
                       and conrelid = 'product_supplier_agreement_link'::regclass) then
            alter table product_supplier_agreement_link add constraint fk_supplier_agreement_target
                foreign key (product_id) references product(id) on delete cascade;
        end if;
        if not exists (select 1 from pg_constraint where conname = 'fk_supplier_agreement_source'
                       and conrelid = 'product_supplier_agreement_link'::regclass) then
            alter table product_supplier_agreement_link add constraint fk_supplier_agreement_source
                foreign key (source_product_id) references product(id) on delete cascade;
        end if;
    end if;
end
$migration$;
create index if not exists ix_supplier_agreement_link_source
    on product_supplier_agreement_link(source_product_id);
commit;
