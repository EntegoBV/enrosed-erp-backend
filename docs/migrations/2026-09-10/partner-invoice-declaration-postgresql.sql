BEGIN;

-- Explicit document wording only. No existing invoices, tax regimes or amounts are changed.
CREATE TABLE IF NOT EXISTS partner_invoice_declaration (
    sales_order_id bigint PRIMARY KEY,
    mode varchar(32) NOT NULL,
    reference varchar(160),
    text_version integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT fk_partner_invoice_declaration_order FOREIGN KEY (sales_order_id)
        REFERENCES sales_order(id) ON DELETE CASCADE
);

COMMIT;
