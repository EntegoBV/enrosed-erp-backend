-- Policy marker for the bounded family-photo website rendition backfill.
--
-- The application migrates only legacy administrator uploads whose small key or checksum is the
-- same as the exact large source. The worker is idempotent and records both resized and deliberate
-- source reuse (animation, EXIF orientation, invalid media, or no byte saving) with this marker.
-- This additive DDL is safe before or after the application deployment and is rerunnable. Existing
-- rows deliberately remain null until the bounded worker has inspected them. At only 133 rows a
-- hardcoded policy-specific partial index would add more migration locking than useful work.

begin;

alter table product_family_photo
    add column if not exists small_rendition_policy varchar(40);

commit;
