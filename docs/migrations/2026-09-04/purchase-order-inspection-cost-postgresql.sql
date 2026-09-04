-- The factory inspection a buyer may order per container, in euro. It is
-- its own line on the order, never folded into the landed cost per piece.
-- Hibernate created the order columns unquoted, so the name folds to lower
-- case here. Rerunnable.

alter table purchase_order add column if not exists inspectioncosteur numeric(19,2);
