-- CIF per container: the supplier books and invoices the sea freight (and the local costs in China),
-- owed as a supplier term of its own, "Zeevracht (CIF)". Additive and rerunnable: one nullable column
-- (null reads as no, nothing is backfilled, so every existing container keeps its figures), and a
-- CHECK that Hibernate may once have generated on purchase_payment.instalment_due learns FREIGHT.
-- Nothing is dropped for good, renamed or rewritten; no payment changes.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE purchase_order ADD COLUMN IF NOT EXISTS freight_via_supplier boolean;

-- Extend only a single-column check on instalment_due that does not know FREIGHT yet;
-- keep its expression and every unrelated constraint.
DO $migration$
DECLARE
    target_table regclass := to_regclass('purchase_payment');
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
          AND a.attname IN ('instalment_due', 'instalmentdue')
          AND position('''FREIGHT''' IN pg_get_expr(c.conbin, c.conrelid)) = 0
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', target_table, enum_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR %I = %L) NOT VALID',
            target_table, enum_check.conname, enum_check.expression, enum_check.attname, 'FREIGHT');
        IF enum_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', target_table, enum_check.conname);
        END IF;
    END LOOP;
END;
$migration$;

COMMIT;
