-- One-time data backfill after the schema deployment added product.colourHex.
--
-- These seven values reproduce the former website presentation exactly.
-- Runtime code deliberately has no colour-name-to-hex mapping: after this
-- backfill, colourHex is editable product master data and the source of truth.
-- Safe to run repeatedly: only rows whose colourHex is still null are touched.

BEGIN;

UPDATE product
SET colourHex = CASE colour
    WHEN 'Red' THEN '#A91F32'
    WHEN 'Pink' THEN '#D889A2'
    WHEN 'Blue' THEN '#6C8FC4'
    WHEN 'White' THEN '#EEE8DD'
    WHEN 'Navy' THEN '#243253'
    WHEN 'Cherry Pink' THEN '#D9577E'
    WHEN 'Light Blue' THEN '#9CC5DE'
END
WHERE colourHex IS NULL
  AND colour IN (
      'Red', 'Pink', 'Blue', 'White', 'Navy', 'Cherry Pink', 'Light Blue'
  );

-- Category now owns the public collection eyebrow. Preserve the existing audited collection copy
-- on rollout; runtime category CRUD subsequently keeps both projections synchronized.
UPDATE category AS category
SET eyebrow = collection.eyebrow
FROM product_collection AS collection
WHERE category.eyebrow IS NULL
  AND NULLIF(BTRIM(collection.eyebrow), '') IS NOT NULL
  AND LOWER(REGEXP_REPLACE(BTRIM(category.code), '[^a-zA-Z0-9]+', '-', 'g'))
      = collection.collectionKey;

-- Prefer the stable canonical variant key for every legacy gallery row.
UPDATE product_family_photo AS photo
SET variant_product_id = product.id
FROM product
WHERE photo.variant_product_id IS NULL
  AND product.familyId = photo.family_id
  AND NULLIF(BTRIM(photo.variantExternalId), '') = product.canonicalVariantKey;

-- Old admin uploads could carry only a colour label. Backfill such a row only
-- when that normalized colour names exactly one SKU in its family. Equal-colour
-- size variants therefore remain deliberately unlinked for manual review.
WITH unique_colour_match AS (
    SELECT photo.id AS photo_id, MIN(product.id) AS product_id
    FROM product_family_photo AS photo
    JOIN product ON product.familyId = photo.family_id
    WHERE photo.variant_product_id IS NULL
      AND NULLIF(BTRIM(photo.variantExternalId), '') IS NULL
      AND NULLIF(BTRIM(photo.variantColor), '') IS NOT NULL
      AND LOWER(REGEXP_REPLACE(BTRIM(product.colour), '\s+', ' ', 'g'))
          = LOWER(REGEXP_REPLACE(BTRIM(photo.variantColor), '\s+', ' ', 'g'))
    GROUP BY photo.id
    HAVING COUNT(*) = 1
)
UPDATE product_family_photo AS photo
SET variant_product_id = matched.product_id
FROM unique_colour_match AS matched
WHERE photo.id = matched.photo_id
  AND photo.variant_product_id IS NULL;

-- Repair the legacy Product.photos compatibility projection that was produced by the old
-- importer. Rows with familyPhotoId are derived and may be rebuilt; rows with familyPhotoId null
-- are user-owned product uploads and are never touched. Photo blobs are not deleted.
--
-- First remove duplicate and sibling/stale projections. Stable product-id links are authoritative,
-- followed by the legacy canonical key only while the FK is null; an image is family-wide only
-- when every variant selector is null.
DELETE FROM product_photo AS duplicate
USING product_photo AS keeper
WHERE duplicate.familyPhotoId IS NOT NULL
  AND keeper.familyPhotoId = duplicate.familyPhotoId
  AND keeper.product_id = duplicate.product_id
  AND keeper.id < duplicate.id;

DELETE FROM product_photo AS projection
WHERE projection.familyPhotoId IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM product
      JOIN product_family_photo AS photo
        ON photo.id = projection.familyPhotoId
       AND photo.family_id = product.familyId
      WHERE product.id = projection.product_id
        AND (
            photo.variant_product_id = product.id
            OR (
                photo.variant_product_id IS NULL
                AND NULLIF(BTRIM(photo.variantExternalId), '')
                    = product.canonicalVariantKey
            )
            OR (
                photo.variant_product_id IS NULL
                AND NULLIF(BTRIM(photo.variantExternalId), '') IS NULL
                AND NULLIF(BTRIM(photo.variantColor), '') IS NULL
            )
        )
  );

-- Normalize every retained derived row to the current large rendition and deterministic order:
-- exact SKU images first, then true family-wide images, after all user-owned photos.
WITH desired_projection AS (
    SELECT
        product.id AS product_id,
        photo.id AS family_photo_id,
        photo.largeStorageKey AS storage_key,
        photo.originalFilename AS original_filename,
        photo.largeContentType AS content_type,
        photo.largeSizeBytes AS size_bytes,
        photo.largeWidthPx AS width_px,
        photo.largeHeightPx AS height_px,
        COALESCE((
            SELECT MAX(user_photo.position)
            FROM product_photo AS user_photo
            WHERE user_photo.product_id = product.id
              AND user_photo.familyPhotoId IS NULL
        ), -1) + ROW_NUMBER() OVER (
            PARTITION BY product.id
            ORDER BY
                CASE WHEN photo.variant_product_id = product.id
                          OR (
                              photo.variant_product_id IS NULL
                              AND NULLIF(BTRIM(photo.variantExternalId), '')
                                  = product.canonicalVariantKey
                          )
                     THEN 0 ELSE 1 END,
                photo.position,
                photo.id
        ) AS desired_position
    FROM product
    JOIN product_family_photo AS photo ON photo.family_id = product.familyId
    WHERE photo.variant_product_id = product.id
       OR (
           photo.variant_product_id IS NULL
           AND NULLIF(BTRIM(photo.variantExternalId), '') = product.canonicalVariantKey
       )
       OR (
           photo.variant_product_id IS NULL
           AND NULLIF(BTRIM(photo.variantExternalId), '') IS NULL
           AND NULLIF(BTRIM(photo.variantColor), '') IS NULL
       )
)
UPDATE product_photo AS projection
SET storageKey = desired.storage_key,
    originalFilename = desired.original_filename,
    contentType = desired.content_type,
    sizeBytes = desired.size_bytes,
    widthPx = desired.width_px,
    heightPx = desired.height_px,
    position = desired.desired_position::integer
FROM desired_projection AS desired
WHERE projection.product_id = desired.product_id
  AND projection.familyPhotoId = desired.family_photo_id;

WITH desired_projection AS (
    SELECT
        product.id AS product_id,
        photo.id AS family_photo_id,
        photo.largeStorageKey AS storage_key,
        photo.originalFilename AS original_filename,
        photo.largeContentType AS content_type,
        photo.largeSizeBytes AS size_bytes,
        photo.largeWidthPx AS width_px,
        photo.largeHeightPx AS height_px,
        COALESCE((
            SELECT MAX(user_photo.position)
            FROM product_photo AS user_photo
            WHERE user_photo.product_id = product.id
              AND user_photo.familyPhotoId IS NULL
        ), -1) + ROW_NUMBER() OVER (
            PARTITION BY product.id
            ORDER BY
                CASE WHEN photo.variant_product_id = product.id
                          OR (
                              photo.variant_product_id IS NULL
                              AND NULLIF(BTRIM(photo.variantExternalId), '')
                                  = product.canonicalVariantKey
                          )
                     THEN 0 ELSE 1 END,
                photo.position,
                photo.id
        ) AS desired_position
    FROM product
    JOIN product_family_photo AS photo ON photo.family_id = product.familyId
    WHERE photo.variant_product_id = product.id
       OR (
           photo.variant_product_id IS NULL
           AND NULLIF(BTRIM(photo.variantExternalId), '') = product.canonicalVariantKey
       )
       OR (
           photo.variant_product_id IS NULL
           AND NULLIF(BTRIM(photo.variantExternalId), '') IS NULL
           AND NULLIF(BTRIM(photo.variantColor), '') IS NULL
       )
)
INSERT INTO product_photo (
    product_id, familyPhotoId, storageKey, originalFilename, contentType,
    sizeBytes, widthPx, heightPx, position
)
SELECT
    desired.product_id, desired.family_photo_id, desired.storage_key,
    desired.original_filename, desired.content_type, desired.size_bytes,
    desired.width_px, desired.height_px, desired.desired_position::integer
FROM desired_projection AS desired
WHERE NOT EXISTS (
    SELECT 1
    FROM product_photo AS existing
    WHERE existing.product_id = desired.product_id
      AND existing.familyPhotoId = desired.family_photo_id
);

-- Backfill the former website card choices through portable canonical keys.
-- No environment-specific numeric product id is embedded in this migration.
WITH family_choice(family_key, variant_key) AS (
    VALUES
        ('rose-diamonds-within-display', 'shopify-46685588127913'),
        ('preserved-single-rose-in-display', 'shopify-46736420765865'),
        ('preserved-bowl-rose', 'shopify-46736421683369'),
        ('bowl-rose-xl', 'shopify-46736106717353'),
        ('cobalt-blue-roos-in-glazen-stolp', 'shopify-44784500277417'),
        ('one-rose-in-box', 'shopify-44784495526057'),
        ('roses-in-box-16pcs', 'shopify-44784490873001'),
        ('roses-in-box-9pcs', 'shopify-44784491397289'),
        ('rose-in-dome-xl', 'shopify-44887957340329'),
        ('soap-rose-box-led', 'shopify-46685592944809')
)
UPDATE product_family AS family
SET cardFeaturedProductId = product.id
FROM product, family_choice AS choice
WHERE family.cardFeaturedProductId IS NULL
  AND family.familyKey = choice.family_key
  AND product.familyId = family.id
  AND product.canonicalVariantKey = choice.variant_key
  AND family.active = TRUE
  AND product.active = TRUE;

WITH collection_choice(collection_key, mobile_name, variant_key) AS (
    VALUES
        ('display-roses', 'Signature displays', 'shopify-46685588095145'),
        ('divers', 'Domes & boxes', 'shopify-44784500277417'),
        ('rose-bears', 'Soap & foam', 'shopify-44784482320553')
)
UPDATE product_collection AS collection
SET mobileName = COALESCE(collection.mobileName, choice.mobile_name),
    featuredProductId = COALESCE(collection.featuredProductId, product.id)
FROM product, collection_choice AS choice
WHERE collection.collectionKey = choice.collection_key
  AND product.canonicalVariantKey = choice.variant_key
  AND product.active = TRUE
  AND EXISTS (
      SELECT 1
      FROM product_family_collection AS membership
      JOIN product_family AS family ON family.id = membership.family_id
      WHERE membership.collection_id = collection.id
        AND membership.family_id = product.familyId
        AND family.active = TRUE
  );

-- The category CRUD projection mirrors the matching merchandising collection.
WITH category_choice(category_code, mobile_name, variant_key) AS (
    VALUES
        ('display-roses', 'Signature displays', 'shopify-46685588095145'),
        ('divers', 'Domes & boxes', 'shopify-44784500277417'),
        ('rose-bears', 'Soap & foam', 'shopify-44784482320553')
)
UPDATE category AS category
SET mobileName = COALESCE(category.mobileName, choice.mobile_name),
    featuredProductId = COALESCE(category.featuredProductId, product.id)
FROM product, category_choice AS choice
WHERE LOWER(REGEXP_REPLACE(BTRIM(category.code), '[^a-zA-Z0-9]+', '-', 'g'))
          = choice.category_code
  AND product.canonicalVariantKey = choice.variant_key
  AND product.active = TRUE
  AND EXISTS (
      SELECT 1
      FROM product_family AS family
      WHERE family.id = product.familyId
        AND family.active = TRUE
        AND (
            family.categoryId = category.id
            OR family.categoryKey = choice.category_code
            OR EXISTS (
                SELECT 1
                FROM product_family_collection AS membership
                JOIN product_collection AS collection
                  ON collection.id = membership.collection_id
                WHERE membership.family_id = family.id
                  AND collection.collectionKey = choice.category_code
            )
        )
  );

COMMIT;
