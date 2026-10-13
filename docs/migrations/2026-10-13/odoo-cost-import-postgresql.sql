-- Durable source identity, independent of later local deletion. No financial history is rewritten.
CREATE TABLE IF NOT EXISTS odoo_cost_import (
    id varchar(100) PRIMARY KEY,
    source_namespace varchar(64) NOT NULL,
    company_id bigint NOT NULL,
    odoo_move_id bigint NOT NULL,
    company_cost_id bigint,
    snapshot_hash varchar(64),
    snapshot_json text,
    documents_json text,
    created_at timestamptz NOT NULL,
    registered_at timestamptz,
    CONSTRAINT uq_odoo_cost_source UNIQUE (source_namespace, company_id, odoo_move_id),
    CONSTRAINT uq_odoo_cost_local UNIQUE (company_cost_id)
);
