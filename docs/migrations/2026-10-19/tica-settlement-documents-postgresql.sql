CREATE TABLE IF NOT EXISTS tica_settlement_document (
    id UUID PRIMARY KEY,
    sha256 VARCHAR(64) NOT NULL UNIQUE,
    file_name VARCHAR(200) NOT NULL,
    content_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    pdf_bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE tica_settlement_document ADD COLUMN IF NOT EXISTS content_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf';
