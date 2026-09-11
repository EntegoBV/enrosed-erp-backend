-- Null keeps existing payments scoped to the whole payee group; no ledger data is rewritten.
BEGIN;
ALTER TABLE purchase_payment ADD COLUMN IF NOT EXISTS instalment_due varchar(16);
COMMIT;
