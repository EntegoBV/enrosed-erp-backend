-- Reversible merges of the Aan wie list. No invoice, payment or bank row changes.
BEGIN;
ALTER TABLE cost_party ADD COLUMN IF NOT EXISTS merged_into_id bigint;
ALTER TABLE cost_party ADD COLUMN IF NOT EXISTS merged_at timestamptz;
ALTER TABLE cost_party ADD COLUMN IF NOT EXISTS merged_by varchar(64);
CREATE INDEX IF NOT EXISTS idx_cost_party_merged_into ON cost_party(merged_into_id);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cost_party_merged_into' AND conrelid = 'cost_party'::regclass) THEN
        ALTER TABLE cost_party ADD CONSTRAINT fk_cost_party_merged_into
            FOREIGN KEY (merged_into_id) REFERENCES cost_party(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_cost_party_not_self_merged' AND conrelid = 'cost_party'::regclass) THEN
        ALTER TABLE cost_party ADD CONSTRAINT ck_cost_party_not_self_merged
            CHECK (merged_into_id IS NULL OR merged_into_id <> id);
    END IF;
END $$;
COMMIT;
