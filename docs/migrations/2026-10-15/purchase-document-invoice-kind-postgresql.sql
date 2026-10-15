-- A container document can be an INVOICE ("Factuur"): an invoice that is not the supplier's commercial
-- invoice (inspection, transport, a courier). Hibernate may once have generated a CHECK on
-- purchase_document.kind; extend only a single-column check that does not know INVOICE yet, keeping its
-- expression and every unrelated constraint. Rerunnable; nothing is dropped for good or rewritten.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

DO $migration$
DECLARE
    target_table regclass := to_regclass('purchase_document');
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
          AND a.attname IN ('kind')
          AND position('''INVOICE''' IN pg_get_expr(c.conbin, c.conrelid)) = 0
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', target_table, enum_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR %I = %L) NOT VALID',
            target_table, enum_check.conname, enum_check.expression, enum_check.attname, 'INVOICE');
        IF enum_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', target_table, enum_check.conname);
        END IF;
    END LOOP;
END;
$migration$;

COMMIT;
