-- Make the existing sales quantity/price basis explicit without repricing stock or orders.
begin;
set local lock_timeout = '15s';
alter table product add column if not exists packagingsalesunit varchar(20) default 'PIECE';

-- These two existing 12-rose articles count four complete displays per carton.
-- In the priced family, 0.25 kg/rose, 3 kg/display and 12 kg/carton confirm the
-- same commercial basis as the existing 49.95 EUR price. No amounts, carton
-- counts, stocks or historical order quantities are converted here.
do $$
declare changed integer;
begin
    perform pg_advisory_xact_lock(hashtext('display-sales-unit-2026-09-22'));
    if not exists(select 1 from catalog_data_patch where patch_key = 'display-sales-unit-2026-09-22') then
        lock table product in share row exclusive mode;
        if exists (
            select 1 from product where familykey in
                ('long-stem-rose-box-display', 'preserved-single-rose-in-display')
            and packagingkind = 'DISPLAY'
            and (packagingpiecesperunit is distinct from 12
                 or piecespercarton is distinct from 4)
        ) then
            raise exception 'Display sales unit: review changed stem-rose packaging before applying';
        end if;
        insert into catalog_data_patch(patch_key, affected_rows, before_state)
        select 'display-sales-unit-2026-09-22', count(*),
               coalesce(jsonb_agg(jsonb_build_object('id', id, 'salesUnit', packagingsalesunit)), '[]'::jsonb)
        from product where familykey in
            ('long-stem-rose-box-display', 'preserved-single-rose-in-display')
            and packagingkind = 'DISPLAY';
        update product set packagingsalesunit = 'DISPLAY'
        where familykey in ('long-stem-rose-box-display', 'preserved-single-rose-in-display')
            and packagingkind = 'DISPLAY';
        get diagnostics changed = row_count;
        raise notice 'Display sales unit: % existing display-priced articles labelled', changed;
    end if;
end $$;
commit;
