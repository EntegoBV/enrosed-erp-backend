-- Free lines a seller writes next to the products of a quote or invoice
-- (assembly, an extra transport leg, a sample), stored as one JSON array on
-- the document. They stay outside the tier discounts. Rerunnable.

alter table sales_order add column if not exists extra_lines_json varchar(4000);
