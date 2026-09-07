-- A partner deal: the container a customer sponsors at our landed cost, and our share of the profit.
alter table sales_order add column if not exists partner_purchase_order_id bigint;
alter table sales_order add column if not exists partner_share_pct numeric(5,2);
