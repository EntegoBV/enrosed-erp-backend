-- Account metadata is additive. No balance, movement or payment is renamed or removed.
-- Alias and canonical keys share one namespace so a name cannot belong to two accounts.
CREATE TABLE IF NOT EXISTS bank_account (
    account_key varchar(120) PRIMARY KEY,
    canonical_key varchar(120),
    label varchar(120) NOT NULL,
    source varchar(24) NOT NULL,
    archived boolean NOT NULL DEFAULT false,
    odoo_source_namespace varchar(64),
    odoo_company_id bigint,
    odoo_journal_id bigint,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_bank_account_odoo_journal UNIQUE (odoo_source_namespace, odoo_journal_id),
    CONSTRAINT ck_bank_account_source CHECK (source IN ('MANUAL', 'ODOO')),
    CONSTRAINT ck_bank_account_alias CHECK (canonical_key IS NULL OR canonical_key <> account_key)
);
CREATE INDEX IF NOT EXISTS ix_bank_account_canonical ON bank_account(canonical_key);
