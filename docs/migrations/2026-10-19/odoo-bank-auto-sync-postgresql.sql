-- Durable scheduling shares the existing Odoo bank lease, so manual and automatic imports cannot overlap.
ALTER TABLE odoo_sync_state ADD COLUMN IF NOT EXISTS bank_next_auto_at timestamptz;
ALTER TABLE odoo_sync_state ADD COLUMN IF NOT EXISTS bank_last_attempt_at timestamptz;
ALTER TABLE odoo_sync_state ADD COLUMN IF NOT EXISTS bank_last_success_at timestamptz;
ALTER TABLE odoo_sync_state ADD COLUMN IF NOT EXISTS bank_last_code varchar(64);

-- Only backfill a prior success when the persisted report explicitly says the import completed.
UPDATE odoo_sync_state
SET bank_last_attempt_at = last_sync_at,
    bank_last_success_at = CASE WHEN last_sync_json ~ '"complete"[[:space:]]*:[[:space:]]*true' THEN last_sync_at ELSE NULL END,
    bank_last_code = CASE WHEN last_sync_json ~ '"complete"[[:space:]]*:[[:space:]]*true' THEN 'READY' ELSE NULL END
WHERE bank_last_attempt_at IS NULL AND last_sync_at IS NOT NULL AND last_sync_json LIKE '%"complete"%';

-- Track immutable TICA settlement source revisions and documents still awaiting review or attachments.
CREATE TABLE IF NOT EXISTS odoo_tica_document (
    id varchar(160) PRIMARY KEY,
    source_namespace varchar(64) NOT NULL,
    move_id bigint NOT NULL,
    document_reference varchar(200),
    source_state varchar(32),
    accepted_hash varchar(64),
    accepted_source_state varchar(32),
    observed_hash varchar(64),
    source_updated_at varchar(64),
    last_seen_at timestamptz NOT NULL,
    last_imported_at timestamptz,
    settlement_id bigint,
    status_code varchar(64) NOT NULL,
    CONSTRAINT uq_odoo_tica_document_source UNIQUE (source_namespace, move_id)
);
ALTER TABLE odoo_tica_document ADD COLUMN IF NOT EXISTS accepted_source_state varchar(32);
