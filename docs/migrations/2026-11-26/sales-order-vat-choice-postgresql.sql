-- A sales document's own VAT route (2026-11-26): through the fiscal representative, the plain Belgian
-- rules, or a service without goods. Only a choice that differs from the customer's standard gets a
-- row; no existing document, tax regime or amount changes. The name is text without a CHECK: an
-- image from before this release never reads the table, and an unknown name reads as "no choice".
-- Rerunnable. Roll back by removing the rows (the documents then follow their customer again).
BEGIN;

CREATE TABLE IF NOT EXISTS sales_order_vat_choice (
    sales_order_id bigint PRIMARY KEY,
    choice varchar(32) NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT fk_sales_order_vat_choice_order FOREIGN KEY (sales_order_id)
        REFERENCES sales_order(id) ON DELETE CASCADE
);

COMMIT;
