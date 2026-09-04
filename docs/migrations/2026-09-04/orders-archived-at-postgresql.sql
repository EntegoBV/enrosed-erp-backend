-- Sales documents and purchase orders can be put away in an archive tab.
-- The moment is server-owned; null keeps the document on the working list.
-- Rerunnable.

alter table sales_order add column if not exists archived_at timestamp(6) with time zone;
alter table purchase_order add column if not exists archived_at timestamp(6) with time zone;
