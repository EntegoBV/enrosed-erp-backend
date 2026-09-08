-- Payments also go to the inspection and other named costs, and to whatever else the container cost to pay for.
do $$
declare c record;
begin
    for c in
        select conname from pg_constraint
        where conrelid = 'purchase_payment'::regclass and contype = 'c'
          and pg_get_constraintdef(oid) ilike '%payee%'
    loop
        execute format('alter table purchase_payment drop constraint %I', c.conname);
    end loop;
end $$;
alter table purchase_payment add constraint ck_purchase_payment_payee
    check (payee in ('SUPPLIER', 'LOGISTICS', 'SEPARATE', 'OTHER'));
