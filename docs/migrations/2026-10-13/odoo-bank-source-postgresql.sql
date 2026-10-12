-- Imported movements keep a stable identity independently of editable bank labels.
ALTER TABLE bank_statement_line ADD COLUMN IF NOT EXISTS source varchar(24);
ALTER TABLE bank_statement_line ADD COLUMN IF NOT EXISTS source_id varchar(180);
ALTER TABLE bank_statement_line ADD COLUMN IF NOT EXISTS source_updated_at timestamptz;
ALTER TABLE bank_statement_line ADD COLUMN IF NOT EXISTS source_reconciled boolean;
CREATE UNIQUE INDEX IF NOT EXISTS bank_statement_source_id ON bank_statement_line(source, source_id);

CREATE TABLE IF NOT EXISTS odoo_sync_state (
    id varchar(64) PRIMARY KEY,
    lease_owner varchar(36),
    lease_until timestamptz,
    last_check_at timestamptz,
    last_check_code varchar(64),
    last_sync_at timestamptz,
    last_sync_json varchar(4000)
);

-- Immutable invoice snapshots and remote operation outcomes survive request/process retries.
CREATE TABLE IF NOT EXISTS odoo_outbox (
    sales_order_id bigint PRIMARY KEY,
    source_namespace varchar(64) NOT NULL,
    marker varchar(180) NOT NULL UNIQUE,
    snapshot_hash varchar(64) NOT NULL,
    snapshot_json text NOT NULL,
    state varchar(32) NOT NULL,
    odoo_move_id bigint,
    peppol_state varchar(40),
    message_code varchar(64),
    updated_at timestamptz NOT NULL
);
