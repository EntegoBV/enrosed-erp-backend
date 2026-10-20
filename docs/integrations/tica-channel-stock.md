# TICA product prices and stock

TICA remains a separate sales channel. Its imported sales totals are source
facts, independent from a product's catalogue price or its editable channel
price. Nothing in this integration writes prices back to the TICA portal.

## Product channel prices

Admin endpoints:

- `GET /api/products/{id}/channel-prices`
- `PUT /api/products/{id}/channel-prices/TICA` with `priceExclVat` and `unit`
- `GET /api/products/{id}/channel-prices/TICA/history`

The effective price is an explicit manual price, or otherwise the latest
positive, active TICA sale with zero discount. Its gross line amount divided
by quantity is the observed unit price, exclusive of VAT. Discounted and
return rows never replace this observed price. This deliberately avoids
inferring a list price from a discounted total.

A null manual price restores the observed price. Changes to the effective
price, unit or source append history; identical saves and repeated imports
do not. A newer observed price remains visible while a manual override is
active. Correcting or retracting source rows recomputes the observation.

The price unit is explicit: `PIECE`, `DISPLAY`, or `TICA_ARTICLE` when the
source unit has not been established. A SKU match alone does not prove a
display was sold. An explicit TICA product-unit mapping takes precedence;
otherwise a display's packaging barcode can establish a display price.

All updates lock the product before loading its channel-price entity. The
synchronizer discovers scalar product IDs first and loads price entities
only after locking; preloading them would allow a stale managed entity to
overwrite a concurrent manual edit.
The source SKU is rechecked against the locked product as well, so a product
rename committed while the import waited cannot retain the previous SKU's price.

## Source-owned stock

A TICA source line is identified by exhibitor, source location, transaction
number and receipt-line number. The imported quantity and the already
applied stock effect are separate facts. A successful complete snapshot
reconciles the difference, within the same database transaction:

- First processing books the effect once.
- An identical sync adds no stock movement.
- A changed quantity books only the difference.
- A return adds stock back.
- A retracted source line compensates its previously applied effect.
- A source/product/location change reverses the old effect before applying
  the new one.

The application state is durable and separate from visible stock history.
TICA revisions and linked stock movements are protected from ordinary
history deletion. A failed or incomplete export never retracts stock.

The source location must be explicitly mapped to an ERP location. The
configured start date prevents importing an arbitrary historical period
into a stock balance that may already include those sales. Initial
activation requires checking existing stock history; an unknown or
ambiguous product/unit remains visible for correction.

## Displays sold as individual stems

The owner confirmed that `STEM-BOX-NV` stock is held in sets of twelve and
TICA sells one stem at a time. Eight complete sets therefore contain 96
stems. One sale leaves seven complete sets and eleven loose stems.

Stock stores complete units plus an integer loose-piece remainder. It
never persists a rounded fraction such as 0.0833 displays. Every movement
retains its unit factor and loose-piece delta, so the historical effect
does not depend on a later packaging edit. Receipts and transfers of whole
sets preserve loose stems; counts explicitly include them. Valuation
includes the remaining stems at their proportional cost.

The warehouse's available complete displays and public catalogue prices
remain based on their existing sales units. TICA's sales-point location
does not contribute to website availability.

## Verification

Regression coverage must include replay, quantity correction, return,
retraction, remapping, immutable history, concurrent stock changes, and
the 96 → 95 → 96 stem round trip. Product-price coverage includes manual
override, reset, retraction, discount exclusion and a manual save that
commits while the synchronizer waits on the product lock.

Live verification must compare before/after per-location balances, source
application records and stock movements, then repeat the same source sync
to demonstrate that neither balances nor revision counts change.
