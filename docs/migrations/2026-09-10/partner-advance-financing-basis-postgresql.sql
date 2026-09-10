BEGIN;

-- A null value retains the historical external-cost agreement. Existing amounts
-- remain unchanged; newly created agreements use the purchase pricing total.
ALTER TABLE partner_advance_agreement
    ADD COLUMN IF NOT EXISTS financing_basis varchar(255);

COMMIT;
