-- A note per purchase order line saying what was wrong on arrival
-- ("glass domes cracked, inner box too thin"). Hibernate created the line
-- columns unquoted, so the name folds to lower case here. Rerunnable.

alter table purchase_order_line add column if not exists issuenote varchar(500);
