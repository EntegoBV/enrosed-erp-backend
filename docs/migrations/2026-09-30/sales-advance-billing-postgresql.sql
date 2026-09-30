-- Advance invoices ("voorschotfacturen") on a regular quote and the final invoice (slotfactuur)
-- that deducts them. Additive and rerunnable: one new side table with its index; sales_order,
-- its purpose and its enum checks stay untouched. stage is a plain varchar (ADVANCE or FINAL),
-- never an enum CHECK; a FINAL row freezes the deducted advances in deductions_json, and an
-- issued ADVANCE row keeps the VAT regime it was issued in (vat_treatment, vat_rate_pct).
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS sales_advance_billing (
    sales_order_id bigint PRIMARY KEY,
    quote_id bigint NOT NULL,
    stage varchar(16) NOT NULL,
    percentage numeric(9,4),
    amount_excl_eur numeric(19,2) NOT NULL,
    deductions_json text,
    vat_treatment varchar(64),
    vat_rate_pct numeric(9,4),
    created_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS sales_advance_billing_quote_idx ON sales_advance_billing (quote_id);

COMMIT;
