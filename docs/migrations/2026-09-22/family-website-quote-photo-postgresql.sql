-- The website quote page shows one administrator-chosen photo per family.
-- Signed ERP photo id like the catalogue choices; null keeps the automatic pick.
-- Additive and rerunnable: never replace an administrator selection.
begin;

alter table product_family add column if not exists websitequotephotoid bigint;

commit;
