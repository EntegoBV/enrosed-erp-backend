-- The letters in front of quote and invoice numbers, editable in settings; the series carries on when they change.
alter table company_profile add column if not exists quote_number_prefix varchar(12);
alter table company_profile add column if not exists invoice_number_prefix varchar(12);
