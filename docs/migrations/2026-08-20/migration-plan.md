# ENROSED canonical catalogue migration

This migration moves product master data into the ENROSED backend while keeping the
operational product form focused on daily purchasing and sales. Public website,
order-app and future catalogue content live on a product family; stock, colour and
operational costing remain on variants.

## Audited sources

| Source | Role | SHA-256 |
|---|---|---|
| `Product (product.template) (3).xlsx` | One-time Odoo export for structured/commercial migration evidence | `a1a22a165bb54cd6284746fbe40c51c9d0a4a7c5f31f0a80d7344040a7b26cd9` |
| `ContainerOverzichtRozen.pdf` | Product/carton dimensions and packaging source | `dec705bfb8e0fee9772dc3babbed793a6acbe1dfe72a1985e35d360662a538a9` |
| `products.generated.json` + `catalog.ts` | Published presentation, order and responsive images | Recorded in the manifest |
| Shopify public product JSON | One-time product/variant/media identifiers and alt-text evidence | Recorded in the manifest |

The workbook contains 41 rows but no SKU/internal-reference column values. One row,
`Productie container voorschot`, is a financial advance and is explicitly excluded.
The PDF contains nine product rows. Its carton arithmetic is exact and reconciles to
18,206 Odoo stock pieces, but every shown unit price conflicts with its shown total.
PDF prices are therefore retained only as unclassified observations.

## Validated target dataset

- Import key: `enrosed-catalog-15bd308e5c212efe`
- 24 families
  - 19 existing website families, published to the website channel
  - 3 Odoo-only families, retained as drafts
  - 2 medium-confidence candidate matches, retained as separate review families
- 58 variants
  - 47 Shopify variants
  - 11 additional Odoo draft/review variants
- 39 variants with an explicit Odoo inventory value
- 19 variants with unknown inventory (stored as unknown, not silently as source zero)
- Known inventory total: 18,206 pieces
- 80 ordered gallery items and 160 self-hosted WebP renditions
  - 156 checksum-distinct blobs (four identical renditions are reused)
  - 480 px and 1200 px rendition for each logical image
  - 50 live Shopify alt texts
  - 30 deterministic existing website fallback alt texts
- 41 recorded warnings/conflicts; no uncertain match is merged

The two review links are:

1. Website `glass-flowerbox` versus two Odoo heart-flowerbox templates and PDF product 8.
2. Website `rose-in-dome-m` versus the four-colour Odoo 15×30 single-dome family and PDF product 5.

They remain distinct until a person resolves the match. The website continues to use
its existing family in both cases.

Odoo and Shopify are not integrations after this migration. Their identifiers and
source observations remain audit provenance only. New edits, publication and media
flow exclusively through the ENROSED backend; the website has no Shopify, Odoo or
hardcoded-product fallback.

## Identifier policy

Matching order is external source ID, confirmed SKU, confirmed barcode, then an
explicit reviewed model/colour decision. No fuzzy automatic merge is used.

- Shopify product IDs are stored on families.
- All 47 Shopify variant IDs are stored on variants.
- Odoo template names and source cell locations are retained as provenance because
  this export has no stable Odoo internal ID.
- Four checksum-valid 13-digit values are stored as unconfirmed packaging-GTIN
  candidates. They are not promoted to sellable-unit EANs without review.
- Because no source contains SKUs, the importer creates deterministic internal SKUs
  with provenance `GENERATED_INTERNAL`; it never claims they came from Odoo/Shopify.

## Price policy

Price contexts are deliberately separate. The manifest contains:

- 8 Odoo structured cost observations, currency unknown
- 29 narrative EXW observations, currency unknown
- 10 France-sales observations: one explicitly EUR and nine with unknown currency
- 3 CIF observations with explicit USD currency
- 1 narrative purchase-price observation, currency unknown
- 47 Shopify retail observations and 6 compare-at observations in verified EUR
- 9 PDF unit and 9 PDF total observations, EUR but otherwise unclassified
- 4 standalone Odoo narrative amounts retained as unclassified observations

Unknown currency, tax treatment or incoterm is kept null. None of those observations
is silently copied into operational CNY EXW or EUR fixed-price fields.

## Media policy

The backend owns the actual bytes after migration. Existing 480/1200 WebP assets from
the generated ENROSED website are imported through the normal database-backed photo
storage. Source URL, Shopify asset ID, checksum, original dimensions, display order,
variant association and alt provenance remain available for audit. Re-running the
same manifest reuses rendition blobs by SHA-256 rather than creating duplicates.

## Clean-slate replacement policy

The production database contains test data only and is replaced deliberately. The
apply operation clears all application/business rows in foreign-key-safe order,
including sales, purchases, customers, suppliers, operational settings, old catalogue
rows and old media references. It preserves only database schema/runtime bookkeeping
required for the application to start. Categories and collections required by the
manifest are recreated from the canonical dataset.

The reset and canonical import run as one explicit transaction. A validation, image
or persistence failure rolls the entire operation back. Production demo seeding is
disabled so an empty database cannot recreate demo products, suppliers, customers or
orders during or after the migration.

## Reproduction

Normal re-runs submit the archived `canonical-catalog-import.json` directly; they do
not contact Odoo or Shopify. To reproduce the one-time extraction itself:

1. Verify the archived workbook, PDF, website snapshot, Shopify JSON snapshot and
   product-image bundle against the hashes in the manifest.
2. Run `generate-canonical-catalog.mjs` with those explicit local source paths.
3. Run `validate-canonical-catalog.mjs` on the import manifest.
4. Submit the manifest to the authenticated migration preflight endpoint.
5. Apply with the explicit full-reset option enabled.
6. Re-run the exact same import and verify counts/checksums remain unchanged.
7. Verify all public family/image responses, the dashboard, and the static website build.

The checked-in `apply-canonical-catalog.mjs` client performs preflight by default and
will not mutate data unless `--apply-full-reset` is present. It reads the login from
`ENROSED_MIGRATION_ADMIN_USERNAME` and `ENROSED_MIGRATION_ADMIN_PASSWORD`, never from
command-line arguments, and accepts plain HTTP only for a local migration server.
Review the preflight table counts before running the explicit full-reset command.

No credentials belong in the manifest, scripts, logs or commits.
