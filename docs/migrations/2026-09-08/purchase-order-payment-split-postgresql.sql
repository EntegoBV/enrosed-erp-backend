-- A payment plan of one's own: percentages at ordering, at departure and at arrival, read under CUSTOM.
alter table purchase_order add column if not exists pay_pct_ordered numeric(5,2);
alter table purchase_order add column if not exists pay_pct_shipped numeric(5,2);
alter table purchase_order add column if not exists pay_pct_arrived numeric(5,2);

-- The preset plans gain a few members; the generated check on the column, whatever it was called, learns them.
do $$
declare c record;
begin
    for c in
        select conname from pg_constraint
        where conrelid = 'purchase_order'::regclass and contype = 'c'
          and pg_get_constraintdef(oid) ilike '%paymentterms%'
    loop
        execute format('alter table purchase_order drop constraint %I', c.conname);
    end loop;
end $$;
alter table purchase_order add constraint ck_purchase_order_payment_terms
    check (paymentterms in ('THIRDS', 'THIRD_TWO_THIRDS_SHIPPED', 'THIRD_TWO_THIRDS_ARRIVED', 'HALF_HALF', 'HALF_HALF_ARRIVED',
        'DEPOSIT_30_70', 'DEPOSIT_30_70_ARRIVED', 'DEPOSIT_30_40_30', 'FULL_UPFRONT', 'FULL_ON_ARRIVAL', 'CUSTOM'));
