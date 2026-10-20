-- No financial values or bank transactions are created by payment-origin metadata.
create table if not exists purchase_payment_origin (
    payment_id bigint primary key references purchase_payment(id) on delete cascade,
    source varchar(32) not null,
    payer_name varchar(100),
    note varchar(500),
    updated_at timestamptz not null,
    updated_by varchar(100) not null,
    constraint purchase_payment_origin_source check (source in ('OLD_BANK_ACCOUNT', 'OTHER_COMPANY')),
    constraint purchase_payment_origin_payer check (source <> 'OTHER_COMPANY' or nullif(trim(payer_name), '') is not null)
);
