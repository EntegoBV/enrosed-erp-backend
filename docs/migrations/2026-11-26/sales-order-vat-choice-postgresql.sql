-- The VAT facts a sales document holds itself (2026-11-26).
--   choice:    its own route where that differs from its customer's standard: FISCAL_REPRESENTATIVE
--              (cleared in the Netherlands by our fiscal representative), BELGIUM (the plain Belgian
--              rules) or SERVICE (a service without goods, only on a document without product lines).
--   treatment, rate_pct, pinned_at: the regime an invoice or credit note left concept with, which it
--              keeps whatever changes on the customer afterwards. SalesVatPinJob fills these for the
--              documents issued before this release, with the regime they are priced with at that
--              moment; no amount or wording changes by that.
-- The names are text without a CHECK: an image from before this release never reads the table, and
-- an unknown name reads as "not recorded". Rerunnable. Roll back by removing the rows (every
-- document then follows its customer live again, as before).
BEGIN;

CREATE TABLE IF NOT EXISTS sales_order_vat_choice (
    sales_order_id bigint PRIMARY KEY,
    choice varchar(32),
    treatment varchar(48),
    rate_pct numeric(7,3),
    pinned_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT fk_sales_order_vat_choice_order FOREIGN KEY (sales_order_id)
        REFERENCES sales_order(id) ON DELETE CASCADE
);

COMMIT;
