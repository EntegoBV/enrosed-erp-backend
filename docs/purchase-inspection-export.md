# Purchase inspection export

The purchase order PDF chooser offers **Inspectiedossier** for an inspector. The
authenticated `GET /api/purchase-orders/{id}/inspection.pdf` endpoint renders a
separate A4 portrait document from the saved order. It does not issue an invoice,
change an order, record an inspection result or send an email.

| Query option | Default | Meaning |
| --- | --- | --- |
| `language` | `EN` | Document labels and available product translations; `NL` is also supported. |
| `includePhotos` | `true` | Include the current product reference photograph. |
| `includeSupplierAgreements` | `true` | Include effective supplier instructions and their reference photographs. |

The document contains supplier and shipment references, ordered products and
colours, quantities, recorded packing specifications and barcodes, plus blank
fields for actual counts, measurements, defects, evidence and sign-off. Original
ordered quantities are used where recorded. Product specifications and photos
come from current catalog records, so the document asks the inspector to compare
them with approved samples. Unknown values and unavailable images are identified.
No acceptance standard or successful inspection result is inferred.

The dedicated inspection template receives a restricted document model. Prices,
costs, margins, payment schedules and internal order notes are not supplied to it.
Supplier instructions and their captions are reproduced as written; the operator
should keep those instructions appropriate for the intended recipient.

## Shared instructions by colour

An agreement stays on one source product. An explicit selection links other
variants of the same family and supplier to that source. The local text and photos
of linked variants remain stored, so unlinking restores their own material.
New variants are not automatically included. The agreement editor displays the
source and linked colours, and requires saved product fields before changing
applicability.

The API uses a revision to reject stale applicability changes. Linking across
families or suppliers, chaining sources, and moving or deleting a source with
active links are rejected. Supplier-facing purchase PDFs and inspection PDFs use
the same effective agreement resolver; shared content is included once with
references to the affected order lines. Separate purchase order lines remain
separate even when they contain the same product.

The additive `shared-supplier-agreements-postgresql.sql` migration creates the
link table without changing existing product notes, images or prices. It is
included in the normal Docker image and pre-deployment migration runner.

## Checks

- Test source selection, unlinking, scope changes and stale revisions with the
  persistence tests for `ProductSupplierAgreementService`.
- Test the inspection renderer and resource, including hidden financial data,
  independent photo options, empty agreements and multi-page content.
- Render fixture PDFs to page images and inspect their pagination and legibility.
- In the frontend, check the product detail and shared agreement editor on mobile
  and desktop, then exercise the purchase PDF chooser, a failed download/retry and
  the unsaved-order guard using isolated API fixtures.
