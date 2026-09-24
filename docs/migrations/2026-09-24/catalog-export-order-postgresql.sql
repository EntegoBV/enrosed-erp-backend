-- Shared ERP catalogue order. Independent of product/family merchandising order.
BEGIN;

CREATE TABLE IF NOT EXISTS catalog_export_order (
    id bigint PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0,
    ordered_ids_json varchar(240000) NOT NULL DEFAULT '[]',
    updated_at timestamp with time zone
);

INSERT INTO catalog_export_order (id, revision, ordered_ids_json)
VALUES (1, 0, '[]')
ON CONFLICT (id) DO NOTHING;

COMMIT;
