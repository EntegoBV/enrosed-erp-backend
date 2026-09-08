-- The Enrosed kost can be spread over the products by hand: each line then carries its own share.
alter table purchase_order_line add column if not exists extra_share_eur numeric(19,2);

-- The Enrosed kost gains the MANUAL key; the generated check on the column, whatever it was called, learns it.
do $$
declare c record;
begin
    for c in
        select conname from pg_constraint
        where conrelid = 'purchase_order'::regclass and contype = 'c'
          and pg_get_constraintdef(oid) ilike '%allocextra%'
    loop
        execute format('alter table purchase_order drop constraint %I', c.conname);
    end loop;
end $$;
alter table purchase_order add constraint ck_purchase_order_alloc_extra
    check (allocextra in ('CBM', 'VALUE', 'PIECES', 'MANUAL'));
