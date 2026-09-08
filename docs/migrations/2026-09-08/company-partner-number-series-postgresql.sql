-- Partner documents number their own series, written from a pattern such as partner/{jaar}/{nr:3},
-- optionally carrying on from where the books already count.
alter table company_profile add column if not exists partner_quote_number_pattern varchar(60);
alter table company_profile add column if not exists partner_invoice_number_pattern varchar(60);
alter table company_profile add column if not exists partner_quote_next_number integer;
alter table company_profile add column if not exists partner_invoice_next_number integer;
