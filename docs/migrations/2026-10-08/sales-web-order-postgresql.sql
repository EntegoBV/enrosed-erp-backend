-- Website orders of logged-in customers. Additive and rerunnable: two new side tables keyed by
-- the sales_order id. sales_web_order holds the order state (its existence is what makes a
-- document a website order); sales_order_delivery holds the delivery address and contact typed
-- for one order. No foreign keys and no value constraints: status-like values are plain strings.
-- The index on sales_order waits for the table, which Hibernate creates on a fresh database.
begin;

create table if not exists sales_web_order (
    sales_order_id bigint primary key,
    customer_id bigint,
    account_id bigint,
    account_email varchar(254),
    language varchar(4),
    revision integer,
    placed_at timestamptz,
    customer_changed_at timestamptz,
    customer_change_summary varchar(1000),
    customer_cancelled_at timestamptz,
    processing_started_at timestamptz,
    processing_started_by varchar(120),
    processing_trigger varchar(16),
    ordered_terms varchar(64),
    sent_terms varchar(64),
    accepted_terms varchar(64),
    order_snapshot text,
    received_mail_sent_at timestamptz,
    processing_mail_sent_at timestamptz,
    mail_error varchar(300)
);

create table if not exists sales_order_delivery (
    sales_order_id bigint primary key,
    customer_id bigint,
    fulfillment varchar(16),
    address varchar(200),
    postal_code varchar(24),
    city varchar(100),
    pickup_location_id bigint,
    pickup_label varchar(255),
    pickup_address varchar(500),
    contact_name varchar(120),
    contact_phone varchar(50),
    copied_from_order_id bigint,
    saved_at timestamptz
);

create index if not exists idx_sales_web_order_customer on sales_web_order (customer_id);

do $migration$
begin
    if to_regclass('sales_order') is not null then
        create index if not exists idx_sales_order_customer on sales_order (customerId);
    end if;
end
$migration$;

commit;
