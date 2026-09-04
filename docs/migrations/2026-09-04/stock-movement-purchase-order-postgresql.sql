-- Damage or a shortage reported after receipt names the container it came on.
alter table stock_movement add column if not exists purchase_order_id bigint;
create index if not exists idx_stock_movement_purchase_order on stock_movement (purchase_order_id);
