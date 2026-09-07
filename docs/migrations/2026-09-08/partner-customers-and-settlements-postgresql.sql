-- Partner customers: who co-orders containers, our default profit share and what they pay up front.
alter table customer add column if not exists partner boolean not null default false;
alter table customer add column if not exists partner_share_pct numeric(5,2);
alter table customer add column if not exists partner_cost_pct numeric(5,2);
-- The auction settlement invoice of a partner deal, marked as such.
alter table sales_order add column if not exists partner_settlement boolean not null default false;
