-- Explicit channel publication for family photos.
--
-- Null is deliberately retained as the compatibility value for rows that existed before this
-- feature: those images keep their current WEBSITE / ORDER_APP / CATALOGUE visibility. New
-- administrator uploads write [] and remain internal until the publication command is used.
-- This is additive, rerunnable, and safe to deploy before application code.

begin;

alter table product_family_photo
    add column if not exists published_channels_json varchar(255);

commit;
