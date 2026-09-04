-- Other named costs a buyer books next to the inspection (certificate, lab
-- test, sample run), stored as one JSON array on the order. Like the
-- inspection they stay off every piece price. Rerunnable.

alter table purchase_order add column if not exists other_costs_json varchar(2000);
