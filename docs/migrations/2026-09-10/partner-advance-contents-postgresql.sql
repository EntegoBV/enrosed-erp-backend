BEGIN;

-- Informational cargo context only. Never contributes to invoice totals or stock.
CREATE TABLE IF NOT EXISTS partner_advance_contents_snapshot (
    sales_order_id bigint PRIMARY KEY REFERENCES sales_order(id) ON DELETE CASCADE,
    snapshot_json text NOT NULL,
    captured_at timestamp with time zone NOT NULL
);

COMMIT;
