-- Who registered a push device, so a creator is not pushed about their own action.
alter table push_subscription add column if not exists username varchar(80);
