-- Goods for a customer cleared in the Netherlands through our limited fiscal representative:
-- the VAT shifts to the customer (art. 12.3 Wet OB) and the documents name the representative.
alter table customer add column if not exists fiscal_representative boolean;
-- A sentence of our own on every document for the customer.
alter table customer add column if not exists invoice_note varchar(500);
-- The representative itself belongs to the company.
alter table company_profile add column if not exists fiscal_representative_name varchar(255);
alter table company_profile add column if not exists fiscal_representative_vat varchar(255);
