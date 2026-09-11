-- Explicit per-order exclusions; legacy zero quantities keep their existing meaning.
BEGIN;
ALTER TABLE sales_order_line ADD COLUMN IF NOT EXISTS unavailable boolean NOT NULL DEFAULT false;
ALTER TABLE sales_order_line ADD COLUMN IF NOT EXISTS requested_quantity integer;
COMMIT;
