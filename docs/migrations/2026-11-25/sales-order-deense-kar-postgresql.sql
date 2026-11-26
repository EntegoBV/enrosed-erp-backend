-- A sales document can go on a Deense kar (2026-11-25): sales_order.palletprofile may hold DEENSE_KAR.
-- Hibernate generated an inline CHECK for the enum column (sales_order_palletprofile_check, palletprofile
-- in EURO_120X80, BLOCK_120X100, HALF_80X60) and schema update never widens a check, so every save of a
-- kar document would fail on it. Extend only a single-column check on palletprofile that knows
-- EURO_120X80 but not DEENSE_KAR yet, keeping its name, its expression and every unrelated constraint.
-- Rerunnable: a later run finds no such check and changes nothing. No row is rewritten.
--
-- Read-only pre-check:
--   select conname, pg_get_constraintdef(oid) from pg_constraint
--   where conrelid = 'sales_order'::regclass and contype = 'c';
--
-- An image from before this release has no DEENSE_KAR: Hibernate cannot read a row that stores it, so
-- every list that loads one answers 500 and the image does not even start (IncomingPaymentBackfill reads
-- every sales order at start-up). Roll back only after moving the kar documents onto a europallet:
--   update sales_order set palletprofile = 'EURO_120X80' where palletprofile = 'DEENSE_KAR';
--   update deleted_item set order_json = replace(order_json, '"palletProfile":"DEENSE_KAR"', '"palletProfile":"EURO_120X80"')
--   where order_json like '%"palletProfile":"DEENSE_KAR"%';
-- Their freight stays FIXED with its amount and their hand units keep the free-text type 'Deense kar';
-- their pallets are then counted on a europallet at the kar's height. The widened check itself is
-- harmless to an older image.
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
          AND a.attname IN ('palletprofile', 'pallet_profile', 'palletProfile')
          AND position('''EURO_120X80''' IN pg_get_expr(c.conbin, c.conrelid)) > 0
          AND position('''DEENSE_KAR''' IN pg_get_expr(c.conbin, c.conrelid)) = 0
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', target_table, enum_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR %I = %L) NOT VALID',
            target_table, enum_check.conname, enum_check.expression, enum_check.attname, 'DEENSE_KAR');
        IF enum_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', target_table, enum_check.conname);
        END IF;
    END LOOP;
END;
$migration$;

COMMIT;
