-- Delivery partitions preserve the existing document IDs and never issue or send documents.
BEGIN;
CREATE TABLE IF NOT EXISTS sales_split_group (
    id varchar(36) PRIMARY KEY,
    root_order_id bigint NOT NULL UNIQUE REFERENCES sales_order(id),
    later_order_id bigint NOT NULL UNIQUE REFERENCES sales_order(id),
    request_id varchar(36) NOT NULL UNIQUE,
    request_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS sales_split_part (
    sales_order_id bigint PRIMARY KEY REFERENCES sales_order(id) ON DELETE CASCADE,
    group_id varchar(36) NOT NULL REFERENCES sales_split_group(id),
    part_number integer NOT NULL CHECK (part_number IN (1,2)),
    waiting_for_stock boolean NOT NULL DEFAULT false,
    pricing_json text NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sales_split_part_group ON sales_split_part(group_id);
CREATE TABLE IF NOT EXISTS sales_customer_request_message (
    sales_order_id bigint PRIMARY KEY REFERENCES sales_order(id) ON DELETE CASCADE,
    message varchar(2000),
    captured_at timestamptz NOT NULL
);
COMMIT;
