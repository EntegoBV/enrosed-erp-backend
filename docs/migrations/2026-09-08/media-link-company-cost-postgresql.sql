-- A file can hang on a booked company cost (the supplier's invoice, the receipt): the target check learns the new type.
alter table media_link drop constraint if exists ck_media_link_target_type;
alter table media_link add constraint ck_media_link_target_type check (
    target_type in ('PRODUCT', 'PRODUCT_FAMILY', 'PURCHASE_ORDER', 'PLANNER_ITEM', 'COMPANY_COST'));
alter table media_legacy_source drop constraint if exists ck_media_legacy_target_type;
alter table media_legacy_source add constraint ck_media_legacy_target_type check (
    target_type in ('PRODUCT', 'PRODUCT_FAMILY', 'PURCHASE_ORDER', 'PLANNER_ITEM', 'COMPANY_COST'));
