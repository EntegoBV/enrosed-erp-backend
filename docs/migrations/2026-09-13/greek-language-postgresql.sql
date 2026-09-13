-- Extend only the existing language enum checks. No stored documents, values or prices change.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
DO $migration$
DECLARE
    enum_check record;
BEGIN
    FOR enum_check IN
        SELECT c.conname, c.convalidated, c.conrelid::regclass AS table_name,
               pg_get_expr(c.conbin, c.conrelid) AS expression
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE c.contype = 'c' AND cardinality(c.conkey) = 1
          AND n.nspname = current_schema() AND a.attname = 'language'
          AND t.relname IN ('category_text','content_translation_text','customer',
                            'product_family_text','product_text','contact_inquiry')
          AND position('''NL''' IN pg_get_expr(c.conbin, c.conrelid)) > 0
          AND position('''TR''' IN pg_get_expr(c.conbin, c.conrelid)) > 0
          AND position('''EL''' IN pg_get_expr(c.conbin, c.conrelid)) = 0
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', enum_check.table_name, enum_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR language = %L)%s',
                       enum_check.table_name, enum_check.conname, enum_check.expression, 'EL',
                       CASE WHEN enum_check.convalidated THEN '' ELSE ' NOT VALID' END);
    END LOOP;
END
$migration$;
COMMIT;
