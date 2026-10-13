-- Completed bank-led local cost creations retain their identity through retries and later corrections/deletions.
CREATE TABLE IF NOT EXISTS bank_cost_registration (
    bank_statement_line_id bigint PRIMARY KEY,
    request_id varchar(36) NOT NULL,
    request_hash varchar(64) NOT NULL,
    company_cost_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_bank_created_cost UNIQUE (company_cost_id)
);
