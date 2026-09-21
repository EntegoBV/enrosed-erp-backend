-- Independent, manually confirmed product units per 20ft GP.
-- Additive and rerunnable; never derive or seed counts from 40ft HC or volume.
begin;

alter table product add column if not exists piecesper20ft integer;

commit;
