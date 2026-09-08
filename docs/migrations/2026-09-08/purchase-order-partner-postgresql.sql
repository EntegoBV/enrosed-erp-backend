-- A container knows its partner itself: who co-orders it, what he pays up front, our share of the profit.
alter table purchase_order add column if not exists partner_customer_id bigint;
alter table purchase_order add column if not exists partner_cost_pct numeric(5,2);
alter table purchase_order add column if not exists partner_share_pct numeric(5,2);
