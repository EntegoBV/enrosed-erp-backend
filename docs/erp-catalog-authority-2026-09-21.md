# ERP catalogue authority — 21 September 2026

The public catalogue now follows current ERP product data across the website,
order-app and catalogue channels. Historical migration observations are audit
records, not fallbacks for current offers or availability.

- Prices use the same fixed-price or cost-plus-markup calculation as ERP sales.
  A nonpositive result produces no public offer. Imported retail and comparison
  prices cannot supply a missing ERP price.
- Availability uses known ERP inventory only. Unknown inventory remains unknown.
- Shared dimensions come from current product masters only when all active,
  non-demo family variants agree on a complete positive triple.
- Saved ERP family photography retains its published gallery order, including
  photographs originally imported from another source. The variant's explicit
  ERP channel lead wins; otherwise the matching family photo, then a shared
  family photo, supplies its primary image. An own product photo is added only
  for an explicit channel lead or when no usable family photo exists.
- Stored photos are ERP-managed assets; provenance does not block publication
  or the existing image URLs. Published family status and active, non-demo
  membership govern access to product images.
  The same projection drives public endpoints, localized image descriptions,
  publication checks, catalogue revisions and catalogue PDF galleries.
- Old external variant identifiers are not public image or packaging identities.
- Existing ERP translations are not replaced by old import literals during
  startup seeding. Missing content can still be initialized.
- The old migration endpoints return 410; extraction and destructive apply
  scripts were removed. Historical records and stable internal identifiers remain
  intact so order references and audit evidence are preserved.

The production audit covered all 23 published families and 55 public variants.
Every variant had a valid current ERP-owned photo. The saved gallery also contains
67 photographs originally imported from Shopify, which remain available as
ERP-managed assets. Thirteen variants had no positive calculable ERP price:
49–52, 66–68, 70–72 and 74–76. Their pages remain available with price on request.
Draft families and demo products remain unpublished. No stock quantities, prices,
publication statuses or photo files were changed by this repair.

The website removes independent product/category photo overrides, consumes exact
ERP inventory states, and generates responsive WebP copies of large ERP originals
during its deployment build. Original URLs remain available for enlargement and
structured data. The mandatory language choice and homepage blog removal remain.

No test suites or local builds are run for this task. Verification consists of
source review, whitespace checks, deployment results and read-only reconciliation
of the live catalogue against the ERP snapshot in all nine languages. Private
database evidence is kept outside the repositories.
