-- Kasprognose: per-order forecast choices, the buffer for "krap", the cost due date and the accounts that count.
-- purchase_order_forecast keeps what Emre chose per container (price, pace, payment days, the expected ship
-- date, a concept's planned order day); a row per order, removed when every choice is cleared. The buffer is
-- one company setting (null reads as EUR 10.000), a cost may carry its own due date (server-owned on the cost
-- PUT) and a bank account can be left out of the forecast start (null reads as counting).
-- Additive and rerunnable: one new table and nullable columns. No foreign key (an order in the trash keeps its
-- choices for a restore), no enum guard (the choices are plain text), no backfill.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

create table if not exists purchase_order_forecast (
    order_id bigint primary key,
    price_mode varchar(16),
    markup_pct numeric(7,2),
    markup_eur_per_unit numeric(19,4),
    cost_basis varchar(16),
    sell_weeks integer,
    payment_days integer,
    seasonal boolean,
    included boolean,
    expected_ship_on date,
    order_date_override date,
    updated_at timestamp(6) with time zone not null,
    actor varchar(255)
);
alter table company_profile add column if not exists cash_buffer_eur numeric(19,2);
alter table company_cost add column if not exists due_date date;
alter table bank_account add column if not exists cash_excluded boolean;

COMMIT;
