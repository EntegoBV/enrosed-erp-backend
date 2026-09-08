-- The inspection and other named costs get a key of their own; null reads as apart from the piece price.
alter table purchase_order add column if not exists alloc_separate varchar(16);
