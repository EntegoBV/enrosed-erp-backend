-- A container document can be a PROFORMA_INVOICE ("Proforma (PI)"): the supplier's proforma, an invoice file of the
-- supplier until the commercial invoice arrives. Hibernate may once have generated a CHECK on purchase_document.kind;
-- extend only a single-column check that does not know PROFORMA_INVOICE yet, keeping its expression and every
-- unrelated constraint. Then the files kept as 'Andere' with a proforma label become that kind; a rerun finds none
-- left. Nothing is dropped for good; only those rows' kind is rewritten.
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
          AND position('''PROFORMA_INVOICE''' IN pg_get_expr(c.conbin, c.conrelid)) = 0
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', target_table, enum_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR %I = %L) NOT VALID',
            target_table, enum_check.conname, enum_check.expression, enum_check.attname, 'PROFORMA_INVOICE');
        IF enum_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', target_table, enum_check.conname);
        END IF;
    END LOOP;

    UPDATE purchase_document SET kind = 'PROFORMA_INVOICE'
    WHERE kind = 'OTHER'
      AND upper(trim(label)) IN ('PI', 'PROFORMA', 'PRO FORMA', 'PRO-FORMA', 'PROFORMA INVOICE', 'PRO FORMA INVOICE');
END;
$migration$;

COMMIT;
