-- The payment that settles its stream, whatever the amount: after it nothing is open, the difference is ours.
alter table purchase_payment add column if not exists settles_stream boolean;
