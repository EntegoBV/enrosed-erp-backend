-- Every sales line remembers what one piece cost us when it was written; older rows are filled at start-up.
alter table sales_order_line add column if not exists unit_cost_eur numeric(19,4);
