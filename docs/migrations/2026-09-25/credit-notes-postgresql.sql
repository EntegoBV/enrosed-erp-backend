-- Credit notes on sales invoices and partner advances.
-- Additive and rerunnable: three nullable columns on sales_order, two on sales_payment (offset pairs),
-- the credit note number prefix on company_profile, and wider enum CHECKs where Hibernate once
-- generated them (sales_order.doctype for CREDITNOTA, deleted_item.type for CREDIT_NOTE). Nothing is
-- dropped, renamed or rewritten; no existing document changes.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE sales_order ADD COLUMN IF NOT EXISTS credited_invoice_id bigint;
ALTER TABLE sales_order ADD COLUMN IF NOT EXISTS credit_reason varchar(40);
ALTER TABLE sales_order ADD COLUMN IF NOT EXISTS goods_returned_at timestamp(6) with time zone;
CREATE INDEX IF NOT EXISTS sales_order_credited_invoice_idx ON sales_order (credited_invoice_id);

-- Hibernate can have generated a varchar enum CHECK on doctype before CREDITNOTA existed.
-- Extend only that single-column check; keep its expression and every unrelated constraint.
DO $migration$
DECLARE
    target_table regclass := to_regclass('sales_order');
    enum_check record;
BEGIN
    IF target_table IS NULL THEN RETURN; END IF;
    FOR enum_check IN
        SELECT c.conname, c.convalidated, a.attname,
               pg_get_expr(c.conbin, c.conrelid) AS expression
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.conrelid = target_table AND c.contype = 'c'
          AND cardinality(c.conkey) = 1
          AND a.attname IN ('doctype', 'doc_type', 'docType')
          AND position('''OFFERTE''' IN pg_get_expr(c.conbin, c.conrelid)) > 0
          AND position('''FACTUUR''' IN pg_get_expr(c.conbin, c.conrelid)) > 0
          AND position('''CREDITNOTA''' IN pg_get_expr(c.conbin, c.conrelid)) = 0
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', target_table, enum_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR %I = %L) NOT VALID',
            target_table, enum_check.conname, enum_check.expression, enum_check.attname, 'CREDITNOTA');
        IF enum_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', target_table, enum_check.conname);
        END IF;
    END LOOP;
END;
$migration$;

-- The history gains GECREDITEERD, VERREKEND and GOEDEREN_RETOUR; the startup migration drops this
-- check too, this keeps a fresh deploy from ever tripping over it.
ALTER TABLE quote_event DROP CONSTRAINT IF EXISTS quote_event_type_check;

ALTER TABLE sales_payment ADD COLUMN IF NOT EXISTS offset_sales_order_id bigint;
ALTER TABLE sales_payment ADD COLUMN IF NOT EXISTS offset_payment_id bigint;
CREATE INDEX IF NOT EXISTS sales_payment_offset_idx ON sales_payment (offset_payment_id);

ALTER TABLE company_profile ADD COLUMN IF NOT EXISTS credit_note_number_prefix varchar(12);

-- The trash names a credit note as its own kind; widen a generated CHECK on deleted_item.type the same way.
DO $migration$
DECLARE
    target_table regclass := to_regclass('deleted_item');
    enum_check record;
BEGIN
    IF target_table IS NULL THEN RETURN; END IF;
    FOR enum_check IN
        SELECT c.conname, c.convalidated, a.attname,
               pg_get_expr(c.conbin, c.conrelid) AS expression
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.conrelid = target_table AND c.contype = 'c'
          AND cardinality(c.conkey) = 1
          AND a.attname = 'type'
          AND position('''INVOICE''' IN pg_get_expr(c.conbin, c.conrelid)) > 0
          AND position('''QUOTE''' IN pg_get_expr(c.conbin, c.conrelid)) > 0
          AND position('''CREDIT_NOTE''' IN pg_get_expr(c.conbin, c.conrelid)) = 0
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', target_table, enum_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR %I = %L) NOT VALID',
            target_table, enum_check.conname, enum_check.expression, enum_check.attname, 'CREDIT_NOTE');
        IF enum_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', target_table, enum_check.conname);
        END IF;
    END LOOP;
END;
$migration$;

COMMIT;
