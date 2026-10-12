-- A bank line carries what it settled through hard links: the purchase
-- payments and the company cost point at it (bank_statement_line_id, set by
-- the bank service when the movement is noted, cleared when the line goes).
-- Additive and rerunnable: nullable columns and their indexes, no foreign key,
-- because the line is the bank's truth and outlives what it carried.
BEGIN;

alter table purchase_payment add column if not exists bank_statement_line_id bigint;
alter table company_cost add column if not exists bank_statement_line_id bigint;

create index if not exists ix_purchase_payment_bank_line on purchase_payment(bank_statement_line_id);
create index if not exists ix_company_cost_bank_line on company_cost(bank_statement_line_id);

COMMIT;
