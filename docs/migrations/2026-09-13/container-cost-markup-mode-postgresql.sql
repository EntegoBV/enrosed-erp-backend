-- Retain the explicit choice of container cost pricing without automatic discounts.
-- Hibernate can have generated a varchar enum CHECK before CONTAINER_COST existed.
-- Extend only the single-column enum check; preserve its previous expression and
-- every unrelated constraint. No existing order or price is changed.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
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
          AND a.attname IN ('markupmode', 'markup_mode', 'markupMode')
          AND position('''PRODUCT''' IN pg_get_expr(c.conbin, c.conrelid)) > 0
          AND position('''ORDER''' IN pg_get_expr(c.conbin, c.conrelid)) > 0
          AND position('''CONTAINER_COST''' IN pg_get_expr(c.conbin, c.conrelid)) = 0
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', target_table, enum_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR %I = %L) NOT VALID',
            target_table, enum_check.conname, enum_check.expression, enum_check.attname, 'CONTAINER_COST');
        IF enum_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', target_table, enum_check.conname);
        END IF;
    END LOOP;
END;
$migration$;
COMMIT;
