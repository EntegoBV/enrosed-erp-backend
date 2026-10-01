# AI context — Enrosed ERP backend

Handover document for any AI assistant (or human) continuing this codebase.
Read this first; the git log tells the same story in finer grain.

## What this is

Internal sales & sourcing ERP for **Enrosed BV**, a Belgian wholesaler of
preserved and soap roses (brand: "Enrosed London"). Built for use at trade
fairs (Aalsmeer) on a phone: quotes are drafted at the table while the
customer watches, so screens are mobile-first and purchase figures can be
hidden at a double-tap.

- Company: Enrosed BV · Vekeblok 17, 2400 Mol, Belgium · BE 1034.273.386
- Contact: hello@enrosed.com · public B2B website: https://enrosed.com (EN at `/`, 8 prefixed locales)
- Frontend repo: `enrosed-erp-frontend` (Angular 22, Vercel)
- This repo deploys to Railway via the `Dockerfile` (Java 25 multi-stage)

## Standing conventions (agreed with the owner)

- **Code and code comments in English.** UI texts and user-facing strings
  are Dutch (the owner works in Dutch). Commit messages in English.
- Commits are grouped per topic ("aparte commits" per feature batch).
- **Translations never live in code.** They are CSV resources under
  `src/main/resources/i18n/` (document-text.csv, colour-names.csv,
  payment-terms.csv, unit-names.csv), 9 languages: NL FR EN DE ES PL PT TR
  EL. A parity test
  fails when any language misses a key. The document-text bundle is an API
  contract: the customer portal (frontend) consumes it via
  `PortalResource`, so keys that look unused in this repo are not dead.
- Dictionaries (colours, payment terms) translate known Dutch keys and
  pass unknown input through untouched.

## Architecture

Quarkus 3.38 on Java 25
(`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`,
run `mvn quarkus:dev`, test `mvn test`). Hexagonal-lite per feature:

```
be.enrosed.catalog | sales | sourcing | shared
   domain/        records, no framework
   application/   services, ports (in/out interfaces)
   adapter/in     REST resources (Basic auth: named staff principals, RolesAllowed)
   adapter/out    Panache entities+mappers, PDF renderers, mail, market
```

Dev DB: H2 file (`./data`, schema update). Prod: Postgres via PG* env vars
(Railway). Domain types are records; construction goes through services;
`withStatus`-style copy helpers keep transitions in one place.

## Domain scenarios (the business rules, as discussed)

### Sales / quotes
- Lifecycle: CONCEPT → VERZONDEN (mail with PDF + portal link) → customer
  BEKEKEN → AKKOORD (digital signature, name recorded) or AFGEWEZEN
  (rejected quotes can be reopened).
- **Revisions**: the customer proposes quantity changes in the portal. We
  answer with Wijzigen (take over, then adjust), Overnemen (take over as
  asked) or Afwijzen. The customer only sees "verwerkt" after we actually
  RE-SEND the quote - never automatically ("honest revision status").
- Delivery terms and freight are three-state: TE_BEPALEN / AANGEVULD /
  known. A second send whose news is the filled-in delivery term says so
  in the mail subject instead of looking like a duplicate mail.
- Pricing: markup modes, country discount tiers, optional extra discount
  with a label that prints verbatim, VAT treatment per customer
  (intracommunautair verlegd prints the legal mention), freight per pallet
  by destination country, minimum order value warning.
- Quantities snap to full cartons **after a 2-second pause** (the seller
  sees a notice immediately, the snap follows) - shared rule with portal.
- Payment terms: standard translated list (PaymentTermsNames); an order-
  level override wins over the customer default ("Van de klant").
- **Hand-built pallets** (optional, never required): a list of pallets on
  the order - label, type (default "Europallet"), height in cm
  (informational), items as cartons per product. When any pallets exist,
  freight counts THEM instead of the calculated stacking; unassigned
  cartons are reported, not blocked. Send works fine without pallets.
- **Packing slip** (`/api/sales-orders/{id}/packing-slip`): no prices;
  groups per pallet when pallets exist, otherwise plain lines; signature
  lines for loading/receipt.
- Quote PDF renders in the customer's language (8 languages, DejaVu fonts
  embedded for PL/TR glyphs); a download may pick a different language
  without changing the customer.
- **Credit notes** (2026-09-25): `DocumentType.CREDITNOTA` on `sales_order`
  with positive lines; the sign is carried by the type (`DocumentSign`
  negates at every ledger boundary: payment summary, partner financing,
  accounting). A credit note is made only from an issued, live invoice
  (`SalesOrderService.createCreditNote`, `proposeCreditNote` prefills the
  container receipt shortage) or, for a partner container that arrived
  short, from the over-financed advance (`PartnerFinancingService
  .creditProposal`, reason PARTNER_SHORTFALL, amount only). It links through
  `credited_invoice_id` (never `sourceQuoteId`), copies customer/purpose/
  partner container/declaration row, is priced with `CONTAINER_COST`,
  markup 0 and explicit 4-dp unit prices (no carton rounding, no tiers of
  its own): the invoiced net unit after the line tier AND the order tier
  and extra discount (`netUnit`, which folds in the subtotal-level
  percentages), a PRICE_CORRECTION line carries cost 0. A PUT on a concept
  keeps customer/country/terms from the stored row and takes every line
  cost from the invoice, never from the client. Caps: per product the invoiced
  quantity, in total the invoice incl. VAT + € 0,01 per line, over LIVE
  credit notes, concepts included. One numbering series `CN-{jaar}-{nr}`
  (`CompanyProfile.creditNotePrefix()`, `ReservedNumber.docType` keeps
  CN out of the F and container series). Lifecycle: issue / mark sent /
  mail (`mailSubjectCreditNote`; from concept either one issues it, with
  the UITGEREIKT and GECREDITEERD events) / reopen / cancel without money
  history / trash type CREDIT_NOTE (restore waits while the invoice is a
  concept); an invoice with a live credit note cannot be reopened or
  deleted; `isInvoice()` stays FACTUUR-only, money flows use
  `isClaimDocument()`. Money: the summary reads a negative total, so
  CREDIT / refund / bank OUTGOING allocation work unchanged; receipts are
  refused; "verrekenen" = `IncomingPaymentService.applyCredit` writes an
  atomic offset pair (−X on the credit note, +X on an invoice of the same
  customer, `offset_sales_order_id`/`offset_payment_id` cross-linked,
  ascending-id locks, container first) that counts as history, is never
  bank-linkable and is voided as a pair. Partner ledgers subtract issued
  PARTNER_ADVANCE credit notes (`invoicedAdvanceEur`, settlement
  availability) so the final settlement credits only the net advance; a
  concept partner credit note blocks a settlement; settlement credit notes
  correct money, never quantities. `returnGoods` books
  `StockMovement.Kind.SALE_RETURN` once, explicitly. PDF: label
  Creditnota, reference + reason line, `Totaal creditnota`, KB nr. 4 VAT
  mention for taxed regimes, own Peppol banner, settlement panel; 23
  document-text keys. Migration `docs/migrations/2026-09-25/credit-notes-
  postgresql.sql` (three `sales_order` columns, two `sales_payment`
  columns, `company_profile.credit_note_number_prefix`, doctype and
  deleted_item CHECK widening; `stock_movement.kind` is a plain varchar).
  The dev H2 file holds `docType`, `markupMode` and `deleted_item.type` as
  native ENUM columns; `InvoiceStatusMigration` widens them to varchar at
  start-up (H2 branch), as it did for status and freight strategy.

- **Container name on sales documents** (2026-09-30): one rule names a
  container everywhere in sales, `PurchaseOrder.displayName()` /
  `PurchaseOrderName.display`: the "Herkenbare naam" (`alias`, trimmed),
  else the number, else "Inkoop #id" (a plain method, never in the order
  JSON). `SourcingRepositories.PurchaseOrders.names(ids)` reads id, number
  and alias as a criteria projection (no lines), so `GET /api/sales-orders`
  names every container once; `OrderView` appends `partnerContainerName`
  and `partnerContainerNumber` for `linkedPurchaseOrderId()` (partner and
  regular container sales, live, null without a container).
  `CreditNoteProposal.container.containerName` and
  `PartnerCreditProposal.containerName` carry it too. The partner credit
  note PDF sentence (`partnerCreditNote`, 9 languages) takes a phrase from
  `partnerContainer` ("container %s") unless our name already starts with
  "container", so it never prints "container container/2026/002". New
  documents print the name in the generated "Voorschot · <naam>" line, the
  inspection/other-cost suffix and the settlement note; internal notes,
  events and the link summary keep the PO number for the audit trail as
  "PO-2026-011 (container/2026/002)" (`containerReference`). Stored lines
  of existing documents are never rewritten.
- **Re-splitting a partner advance plan** (2026-09-30, reopened concepts
  2026-10-01): a schedule row is FIXED only when its invoice is not CONCEPT,
  has payment history (`hasHistory`, voided receipts and `paidAt` included)
  or a live credit note (`SalesOrderService.scheduledAdvanceFixed`, the one
  rule for the JSON, `save()` and the revise re-check under the lock). A
  CONCEPT is revisable, also one that was issued and reopened (Verhoeven BV,
  container/2026/009): it keeps its number; deleting it stays refused, for a
  scheduled PARTNER_ADVANCE term in CONCEPT with 409 "Factuur {nr} was al
  uitgereikt; het nummer blijft bestaan. Pas de verdeling aan via Termijnen
  aanpassen op de inkooporder." (an issued term and other documents keep
  their text). The revise drops a leading term sentence that invoices of
  2026-09-08..10 stored in `notes` ("Voorschot · <old label>. ... van het
  afgesproken voorschot. ..."), keeping the buyer's note after it.
  `PartnerAdvanceScheduleService.Row.invoiceFixed` says so in the JSON, and
  `invoiceReopened` marks a concept that was issued or sent before.
  `save()` freezes FIXED rows only; a row linked to a concept
  may change label, amount and due date and the concept follows in place in
  the same transaction (`SalesOrderService.reviseScheduledPartnerAdvance`:
  same number, its one "Voorschot · <label>" line at the new amount, the
  row's due date or the old one, OPGEMAAKT event "Voorschottermijn
  aangepast: € A → € B", a purchase-order diary line). A concept row cannot
  be dropped (409 "Termijn met conceptfactuur {nr} kan niet weg; ..."); new
  rows get no invoice until "Conceptfactuur maken". While any row is fixed,
  a plan that adds or changes rows must cover the whole agreed advance (409
  "Verdeel het resterende voorschot volledig: nog € X te verdelen."); merely
  dropping unused open rows before a settlement may still leave part
  unplanned (`unusedScheduleRemainderCanBeRemovedBeforeSettlement...`). The
  residual cent of an all-percentage plan goes to the last open row, else to
  the last concept row.
- **Partner advance credit proposal** (2026-09-30,
  `PartnerFinancingService.creditProposal`): the percentage is the saved
  agreement's `financingPct` (fallback `partnerCostPctOrDefault()`), and a
  suggestion is made only when the agreement basis is
  PURCHASE_TOTAL_WITH_SEPARATE_COSTS (a legacy EXTERNAL_FORECAST suggests 0).
  Two meanings live side by side on purpose: `overFinancingEur` stays
  issued-only (the partner-payments card relies on it), while
  `wanted = max(0, over - pendingCreditEur)` subtracts live CONCEPT
  PARTNER_ADVANCE credit notes (`pendingCreditNumbers`), so the same
  shortfall is never proposed twice. Each `AdvanceOption` appends
  `vatRatePct`, `maxCreditEur` (room excl. VAT, rounded down),
  `openEur` (still to be paid, incl. VAT like the payment summary) and
  `suggestedCreditEur = min(wanted, room)`.
  The target (`suggestedAdvanceInvoiceId`) is the latest unpaid advance that
  can absorb the credit: advances with room first, then open >= wanted incl.
  that advance's VAT, room >= wanted (both excl.), highest id. `suggestedCreditEur`/`...InclVatEur` is what
  fits on it, `remainingCreditEur` what does not; `shortValueEur` (ordered
  basis minus received basis, `PurchaseOrderService
  .calculateForOrderedQuantities`) is informative only.
- **Voorschotfacturen and slotfactuur on a regular quote** (2026-09-30,
  `SalesAdvanceBillingService`, a whole container sold to e.g. a French
  customer and paid in parts). Side table `sales_advance_billing`
  (`SalesAdvanceBilling`, migration
  `docs/migrations/2026-09-30/sales-advance-billing-postgresql.sql`:
  sales_order_id PK, quote_id, stage varchar ADVANCE|FINAL without a
  CHECK, percentage, amount_excl_eur, deductions_json, vat_treatment,
  vat_rate_pct, created_at); nothing
  on sales_order, purpose stays STANDARD (`!== 'STANDARD'` means partner in
  many places). `POST /api/sales-orders/{quoteId}/advance-invoice`
  `{percentage | amountEur, dueDate?}` makes a CONCEPT F-series FACTUUR
  without product lines, freight FIXED 0, one line "Voorschot 30 % · offerte
  OF-…" (or "Voorschot · offerte OF-…"), the quote's customer, country,
  incoterm, terms and container link, `sourceQuoteId` null (the quote's one
  invoice stays its slotfactuur); base = the quote total excl. VAT, live
  advances (concepts included, each net of its issued credit notes) never
  exceed it; a percentage that overshoots the rest by its rounding only
  (half a cent per live percentage advance, this one included) takes the
  rest, so 50 % + 50 % of € 6.468,01 is 3.234,01 + 3.234,00. Refused for invoices,
  partner/agreement quotes, archived or split quotes, rejected/expired/
  cancelled quotes, a quote with an invoice, freight TE_BEPALEN. The server
  owns an advance's line and freight (update keeps them, freight endpoints
  refuse); no minimum order value on it. While a live advance exists the
  quote cannot be deleted, reopened or cancelled and its customer/country
  are frozen; lines may still change. An issued advance its issued credit
  notes took back in full (no concept credit waiting) no longer counts
  there, as the slotfactuur skips it too. The slotfactuur is the ordinary
  `createInvoiceFrom`: every live advance must be issued, without a concept
  credit note, in the quote's VAT treatment and rate (an issued advance
  stores the regime it was issued in, `SalesAdvanceBillingService
  .afterIssue` on issue and on mark-sent from concept; the slotfactuur is
  refused at creation and at issue when that differs, since both documents
  otherwise re-price live from the same customer); each is deducted on a
  server-owned line "Voorschotfactuur F-… van dd/mm/jjjj" at -(total excl.
  VAT minus issued credit notes), so VAT falls on the balance; the FINAL row
  freezes the deductions and `update()` re-applies them. Issuing refuses a
  negative balance ("maak een creditnota op een voorschotfactuur") and a
  deduction that no longer matches its advance (recreate the concept).
  Deleting a concept slotfactuur deletes its FINAL row; ADVANCE rows stay
  and are read through live documents only. An advance on an issued
  slotfactuur cannot be deleted, reopened or credited: the credit note goes
  on the slotfactuur, whose cap (and `CreditNoteProposal
  .maxCreditInclVatEur`) is its own total plus the deducted advances incl.
  VAT, so a short delivery after a full prepayment is still creditable. A
  slotfactuur of € 0 (the advances covered everything) is BETAALD on issue
  (paidAt = that moment, event "Slotfactuur volledig verrekend met de
  voorschotfacturen"): a receipt on zero is refused and it must never read
  overdue. Turnover: an advance
  recognises its own total and no cost, the slotfactuur its net total with
  the full cost, so all documents together are the sale once (no accounting
  code needed). `OrderView` appends `advanceBilling`, `advanceInvoices`
  (quotes; [] when none; `creditedExclEur` = the issued live credit notes
  on it, excl. VAT) and `advanceDeductions` (slotfactuur, with `paidOn` =
  the day the payments first covered the deducted `inclEur`, i.e. the
  advance net of its issued credit notes, and `receipts`). `receipts` leave
  out offsets from the advance's own credit notes: those are the credit,
  never a payment. PDF: "Voorschot-
  factuur"/"Facture d'acompte" with the line printed as `advanceOnQuote`
  ("Acompte sur le devis OF-… · 30 %") in the document language;
  "Slotfactuur"/"Facture finale" with a "Verrekende voorschotfacturen"
  block (number, date, excl., VAT, incl., "betaald op"/"nog open") and
  totals Totaal, - voorschotten, `advanceBalance`, VAT on the balance,
  `advanceStillToPay`; the deduction lines print once (`documentExtras`).
  Mail subjects `mailSubjectAdvanceInvoice` / `mailSubjectSettlementInvoice`.
  Trash: a slotfactuur of a quote with advances is recreated, not restored;
  an advance is restored only while its quote has no invoice and room left.

### Purchasing / landed cost
- Purchase order = one container from a Chinese supplier. Lines hold an
  EXW price **in the currency it was agreed in** (CNY/USD/EUR) with rates
  frozen on the order (cnyToUsd, usdToEurGoods, usdToEurTransport).
- Landed cost per piece: goods + origin costs (China, inside customs
  value) + sea freight to the destination port + duty per HS code +
  destination→warehouse trucking (outside customs value) + optional
  "extra revenue" the seller wants folded in. Allocation modes decide how
  shared costs spread over lines. Container fill/overflow is computed.
- Status: CONCEPT → BESTELD → ONTVANGEN. (ONDERWEG still exists in the
  enum for old rows but the UI stepper hides it.) Leaving CONCEPT
  snapshots `orderedQuantity` per line so short shipments stay visible;
  arrival books stock.
- Purchasing **warns and never rounds** quantities (a sample of 3 pieces
  is legitimate; silently inflating an order costs real money).
- "Kostprijzen toepassen" writes landed cost per piece onto the products
  (asks confirmation - every sales margin recalculates from it).
- Purchase PDF has an internal variant (extra revenue as its own line)
  and a customer-safe variant (folded into the piece price).
- Container payments carry an optional **bank euro amount** (2026-09-25):
  `PaymentRequest.amountEur` on POST/PUT `/purchase-orders/{id}/payments`.
  Absent, the order's frozen rates convert as before (and a PUT that
  leaves amount and currency unchanged keeps the stored euro value);
  given, it is stored as the euro value (`purchase_payment.amount_eur`,
  no schema change) and audited as `payment.amountEur` "In euro". Rules:
  > 0, and equal to the amount for a EUR payment. The reconciliation, the
  payments PDF register, supplier allocation and `attention()` all keep
  reading the stored `amountEur`.
- **Receipt day** (2026-09-29): `receive()` stores the `receivedOn` the
  sheet sends, or today in **Europe/Brussels** (the Railway clock is UTC,
  so `LocalDate.now()` gave yesterday after midnight). A sent day may not
  lie in the future (Brussels) nor before the order date (409 "Ontvangen op
  kan niet in de toekomst liggen" / "... vóór de orderdatum liggen"). A
  plain PUT on an ONTVANGEN order applies a non-null, different
  `receivedOn` as a correction with the same checks; null, or any other
  status, keeps the stored day (payment writes PUT merged payloads). The
  "Ontvangst dd/mm/jjjj" diary line stays as written; the audit diff
  "Ontvangen op" records the correction.
- **Supplier credit, "Tegoed leverancier"** (2026-09-29): money the
  supplier owes after a short delivery, damage or a wrong price. Own table
  `purchase_supplier_credit` (migration
  `docs/migrations/2026-09-29/purchase-supplier-credit-postgresql.sql`),
  domain `PurchaseSupplierCredit` (reason SHORTAGE/DAMAGE/PRICE/OTHER =
  Tekort/Schade/Prijsverschil/Andere; status OPEN/OFFSET/REFUNDED = Tegoed
  open/Verrekend/Terugbetaald), `PurchaseSupplierCreditService`. Never a
  negative payment: every reader of `purchase_payment` counts money out.
  Endpoints POST/PUT/DELETE `/purchase-orders/{id}/supplier-credits[/{creditId}]`
  and POST `.../{creditId}/offset` all answer the updated `PurchaseOrderView`,
  which lists `supplierCredits` (always an array) and, on a container that
  took an offset, `creditOffsets`. Euro at the order rate unless an
  `amountEur` is sent; all credits of an order together stay within the
  supplier Afspraak (checked when a credit is noted or its amount,
  currency or euro changes; a PUT to REFUNDED that keeps amount and
  currency only records the bank euro and is never capped, because the
  rate may have moved). Only an OPEN credit changes or is deleted;
  REFUNDED needs `settledOn` and may carry the bank euro; back to OPEN
  undoes the refund. OFFSET only through `/offset`: an ordinary SUPPLIER payment
  "Verrekend tegoed PO-A" on another, non-concept container of the same
  supplier, in the credit's amount and currency at that container's rate
  (its terms close exactly). Deleting that payment reopens the credit;
  `updatePayment` refuses amount, currency and payee changes on it and a
  new date moves the credit's `settledOn`. Reconciliation: the SUPPLIER
  stream carries `creditEur` (every credit, whatever its status) and
  `forecastEur = paid + open - credit` (paid and open stay bank truth,
  status words unchanged, `finalized` untouched); lines get `creditEur` by
  missing value (SHORTAGE), damaged value (DAMAGE), goods otherwise;
  totals add `supplierCreditEur` and `supplierCreditOpenEur`. Partner
  settlements follow the lower line cost. Diary lines ("Tegoed leverancier
  genoteerd/terugbetaald/verrekend met ...") are rebuilt from the credit so
  a change rewrites its own line. A container with a credit is archived,
  never deleted.
- **CIF per container** (2026-09-29): `PurchaseOrder.freightViaSupplier`
  (column `freight_via_supplier`, migration
  `docs/migrations/2026-09-29/purchase-order-freight-via-supplier-postgresql.sql`,
  which also widens any generated CHECK on `purchase_payment.instalment_due`
  for FREIGHT; dev H2 via `AllocationDevSchemaFix`). Null reads as no and is
  ignored when every line is DDP (`order.cif()`). Chosen on the container,
  never read from the supplier's incoterm (ac669c3 removed exactly that);
  `create()` only presets it once when the client sends nothing and the
  supplier quotes CIF or CFR. `payable()`: CIF moves origin + sea freight
  (`supplierFreightEur`) from Douane & transport to Leverancier; duty and
  arrival costs stay; `freightInSupplierPrice = ddp || cif`; customs value,
  duty and cost prices never move. The payment plan's percentages split
  the goods only; the freight is one extra supplier term
  `PaymentTerms.Moment.FREIGHT` "Zeevracht (CIF)", due like SHIPPED, placed
  before any ARRIVED term (`SupplierPaymentAllocation.calculate(order,
  goods, freight, payments)`). FREIGHT is refused on anything but a
  supplier payment of a CIF container. Turning CIF on or off is refused
  while a Leverancier or Douane & transport payment carries a settle
  marker, and turning it off while payments are tied to FREIGHT.
  Reconciliation weights keep every product at its EXW cost: SUPPLIER =
  goods (+ transport under CIF), LOGISTICS = duty + arrival (+ transport
  otherwise). The landscape PDF says "goederen + zeevracht" with a CIF
  note and lists the freight term; the portrait "Prijsbasis" prints the
  container's own EXW/CIF/DDP. **Revert or image rollback**: code from
  before this change has no `Moment.FREIGHT`, and Hibernate then fails on
  every payment row tagged with it (one such row makes the whole Inkoop
  list answer 500). Before reverting it or rolling Railway back past it,
  run `UPDATE purchase_payment SET instalment_due = NULL WHERE
  instalment_due = 'FREIGHT';` and check those payments by hand: they then
  fill the earliest open supplier term, and a CIF container's freight goes
  back under Douane & transport, so its Leverancier looks overpaid. The
  `freight_via_supplier` column and the widened check can stay; older code
  ignores them.

### Catalog / products
- Product: SKU, name, colour (translated via dictionary), sizes, carton
  (pieces per carton drives all rounding), barcodes (piece EAN-13 + outer
  ITF-14), HS code, EXW price + currency, landed cost + source, markup or
  fixed sales price, stock, photos (stored IN the database as blobs -
  survives Railway redeploys; port `PhotoStorage` allows an S3 later).
- Native Excel master-data and translations exchange in one guided workbook
  (8 languages); the older CSV endpoints remain available for compatibility.
- Catalogue PDF: language choice, chapters per category with
  descriptions, two full photos per product card.
- Brochure family sheets are fixed A4 pages that clip whatever does not
  fit. `PdfCatalogRenderer.render` lays the brochure out first;
  `FamilySheetFit` compares each sheet's content bottom with its footer
  (2 mm clearance, the same line as the tests' 795 pt check), and sheets
  that run into it are laid out again in the compact design. `renderHtml`
  shows the first layout only.
- **Photo export (ZIP)**: `POST /api/products/photo-export` (admin) takes
  `{scope: ACTIVE|WEBSITE|ALL, photos: ALL|WEBSITE, language}` and returns a
  15-minute `downloadUrl`; `GET /api/products/photo-export/{token}` is
  PermitAll (the 256-bit token is the authorization) so the browser
  downloads natively. One folder per SKU (`SKU - name - colour[ - size]`,
  Windows/macOS-safe), `01-hoofdfoto` = `Product.photoForSalesDocument()`,
  then website photos, then the rest; always the stored original bytes
  (family photos: the `large` object, never `small`), STORED entries, one
  blob in memory at a time. LEESMIJ.txt/README.txt is written last, texts
  from `i18n/photo-export-text.csv`, and carries no prices, costs,
  supplier, stock or HS code. Tickets live in memory
  (`PhotoExportTokenStore`): this assumes one backend instance.
- Product remains the stock-bearing SKU. Optional `familyKey` groups variants;
  unique `publicHandle` is the stable public identity. WEBSITE and ORDER_APP
  each have DRAFT/READY/PUBLISHED state; legacy and new rows default DRAFT.
- `Product.publicationIssues()` is computed and publication is rejected until
  active/content/category/photo/price/carton/handle requirements are complete.
- Public consumers use `GET /api/v1/public/catalog?channel=WEBSITE&language=EN`.
  It is a purpose-built safe DTO: no supplier, cost, margin, HS code, internal
  source or exact stock. Public photo bytes have a separate PermitAll route and
  remain inaccessible unless the SKU is published on at least one channel.

### Photo roles (2026-09-22)
- Photos are keyed `F<familyPhotoId>` (series photo, "reeksfoto") and
  `P<productPhotoId>` (own photo, "losse productfoto"); the signed ids of the
  website/catalogue choices map onto them (positive F, negative P).
- `GET /api/products/{id}/photo-overview` is the one read model for the ERP
  photo section; `PUT /{id}/photo-roles` (MAIN, QUOTE, CATALOGUE_VARIANT,
  CATALOGUE_OVERVIEW, CATALOGUE_DETAIL) and `POST /{id}/photos/promote`
  ("Zet in de reeks") answer with it. Picking an unpublished series photo for
  a role publishes it for that channel first.
- `ProductFamilyEntity.websiteQuotePhotoId` is the quote-page photo
  (`WebsiteQuotePhotoChoice`): a stored choice counts only while it is in the
  WEBSITE gallery, else the first colour's primary. Public `quoteImageId` and
  the revision digest carry the resolved id; the general family PUT never
  writes it.
- Explicit alt texts are optional: `FamilyPhotoAltText` generates
  "family name — colour" in the requested language, so publishing a series
  photo no longer waits for nine hand-written alts. Explicit alts still win.
  Exception: a legacy row with `published_channels_json` null keeps its old
  rule (all channels only with a non-blank explicit alt, else internal), so
  the deploy publishes nothing new; the publication command stores explicit
  channels and then the generated alt applies.
- The overview's automatic Hoofdfoto (`main`, explicit false) is what quotes,
  invoices, portal and ERP lists print (`Product.photoForSalesDocument`: first
  series projection). Series photos carry `publishedChannels` (stored choice)
  next to the effective `visibility`; channel switches start from the former.

### The Maat is one language-neutral value (2026-09-28)
- Owner decision after the diamond displays printed 4.5*4.5cm in every
  language while their Maat said 4.8*4.8cm (stale per-language copies left
  by the startup backfill): the Maat (`product.variantSize`) is not
  translated at all. Every output prints the base as typed in every
  language: quote/invoice PDF, PDF catalogue (brochure, compact, simple),
  purchase PDF (supplier-facing too), inspection brief, photo-export folder
  names, `Product.describeIn` and public `VariantDto.size`.
  `textSources.size` is the requested language whenever there is a size
  (exact in every language, like `unit`), so the strict endpoint, the
  strict PDF check and `PublicLocalizationCompletenessService` never miss
  a size and `websiteBuildReady()` never waits for one.
- `ProductText` has no size. `product_text.variantsize` stays in the schema
  (Hibernate validation, H2 dev files, rollback) but is retired: never read,
  and every save through `CatalogMapper` or the translation endpoint writes
  null; a row left with nothing but a size is empty and is dropped.
  `ProductDto.TextDto.variantSize` stays in the JSON for older ERP clients:
  always null in responses, ignored in requests (also not length-checked).
  The translation CSV has no `maat` column any more (`sku;taal;naam;
  beschrijving;kleur`); an older file with a sixth `maat` column, or an
  older workbook with `Variantmaat` on Vertalingen, imports without it.
- The startup backfill seeds colours only. Shared fields and duplicate copy
  names, descriptions and colours; a new Maat on a duplicate is just the
  new base (`VariantSizes`, Small -> Klein, is gone).
  `Product.textsFollowingColourChange` keeps only the colour rule: copies of
  the old base colour follow a new base, real translations stay.
- The website revision hashes `product.variantSize` and no per-language
  size, so a base-size edit changes it and queues a rebuild; a leftover
  per-language value does not. Dropping that term changes the revision once,
  so the first start of this release queues one rebuild.
- The translation endpoint's revision keeps a constant empty placeholder
  where it hashed the per-language size: a row without a size keeps its
  revision over the deploy, so an ERP translation tab or an AI translation
  batch copied before it still saves. Only products whose rows held a size
  (cleared or deleted by the migration below) get a new revision, once.
- `docs/migrations/2026-09-28/product-text-variant-size-neutral-postgresql.sql`
  (marker `product-text-variant-size-neutral-2026-09-28`, advisory lock,
  early return, listed after the 2026-09-27 repair) runs once: a product
  without a base Maat whose translations carried one takes the English (then
  Dutch, then any) value as its base, except when the activity log records a
  Maat edit of that product (an editor emptied it; before 17ff7ff a base edit
  left the copies behind) or an active product of the same family already
  has that colour and size (`FamilyVariantRules`: a duplicate option blocks
  the family's website build and every further edit). Then every
  `product_text.variantsize` is cleared and rows left with no name, public
  name, description or colour are deleted. `before_state` holds
  `promotedBaseSizes`, `clearedSizes` (skipped promotions included) and
  `deletedEmptyRows`. No DROP: the column stays. H2 dev is not migrated;
  its leftover copies are never read and disappear on the next save.
- Before the production deploy, list the candidates read-only and show Emre
  which Maat is promoted and which stays empty:
  `select p.id, p.sku, p.familyid, p.colour, p.active, t.language,
  t.variantsize, exists(select 1 from activity_log a where
  a.entity_type='PRODUCT' and a.entity_id=p.id::text and a.changes_json
  like '%"variantSize"%') as maat_edited from product p join product_text t
  on t.product_id=p.id where nullif(btrim(p.variantsize),'') is null and
  nullif(btrim(t.variantsize),'') is not null order by p.id, t.language;`
- The schema stays compatible, the rendering of an older image does not: an
  image before this release wants an explicit per-language size in its
  strict projection and `PublicLocalizationCompletenessService`, so on the
  cleared data it reports every Maat missing (strict builds refused,
  `websiteBuildReady()` off). A website build that starts between the
  pre-deploy migration and the switch fails; the new release queues a fresh
  one on startup. Do not start or retry a build by hand during the deploy.
  Rollback: before or right after starting the older image, run the two
  restore statements in the migration header (re-insert `deletedEmptyRows`,
  then write `clearedSizes` back by product and language).

### Sales units: what one piece is called (2026-09-22)
- `Packaging.salesUnit` stays the commercial basis (PIECE or DISPLAY);
  `Packaging.unitKey` (`product.packagingunitkey`, nullable = "stuk") names
  the piece: stuk, bowl, stolp, box, roos, hart, beer. Translations and plural
  forms (Polish few/many, the whole "per" phrase) live in
  `i18n/unit-names.csv`, read through `shared/UnitNames`; the "stuk" rows
  reproduce the old wording exactly. Unknown keys are rejected on write and
  read as "stuk".
- A null `packaging.unitKey` on the product PUT keeps the stored unit (also
  when `packaging` is null); PACKAGING shared fields and duplicates copy it;
  the Excel/CSV exchange has it as the last column `eenheid`.
- Documents (owner decision, a65c3f4): a product packed in a display leads
  with the price of one full display, like the website, portal and
  catalogue: "€ 31,60 per display van 8 bowls" (priced per bowl) or
  "€ 60,00 per display" (priced per display), then the piece price
  ("€ 3,95 per bowl" / "≈ € 5,00 per bowl") and the display/loose counts.
  Plain pieces print "per bowl". A display carton reads "5 displays per doos
  (40 bowls)", one carton noun per line. `sales/application/SalesUnitText`
  holds the shared quantity phrases (PDF, packing slip, mail).
- Clients get `UnitDto {key, one, few, many, other, short, per}` in their
  language: portal lines/catalogue, public `VariantDto.unit` (source always
  exact, `textSources.unit`) and `PublicQuoteDtos.ProductPrice.unit` (kept when
  prices are hidden). `GET /api/products/unit-names` is the Dutch ERP pick-list.
- Reworded seed copy never reaches production (the loader only inserts
  missing keys): unit-aware catalogue wording uses new keys
  (`catalog.quantity.displayunits`, `catalog.price.settotalunits`, ...).

### Translation system and public website (Codex, 2026-08-21)
- **Content translations**: `ContentTranslationEntity` + texts per language,
  scoped (`ContentScope`: website copy, legal pages, categories, product
  families). Seeds ship as CSV/JSON under `src/main/resources/i18n/`
  (`website-content.csv`, `public-content.csv`, `catalog-family-copy.json`,
  `catalog-content-backfill.json`); `PublicContentSeedLoader` loads them,
  `CatalogContentBackfillService` fills gaps on existing data.
- **Strict localization**: public catalogue endpoints take
  `strictLanguage=true`; `PublicLocalizationCompletenessService` lists
  every missing path and `LocalizationIncompleteException` refuses to
  serve a language with holes. `ProductFamilyWriteGuard` blocks any edit
  that would make a PUBLISHED/READY family incomplete. The general family
  PUT never overwrites atomic translations (owned by the revisioned
  translation endpoints).
- **Category optimistic locking**: `Category.revision` (@Version); every
  save requires the revision the editor observed. Child-only text edits
  dirty the aggregate via `updatedAt` so the version bumps at flush - a
  forced increment would only land at commit, after the API answered.
- **Website rebuild outbox**: mutations enqueue one debounced
  `WebsiteRebuildEntity` row; a scheduler calls the Vercel deploy hook
  outside the business transaction (`VERCEL_WEBSITE_DEPLOY_HOOK_URL`) and
  polls `WEBSITE_PUBLIC_REVISION_URL` (the site's catalog-revision.json)
  until the deployed revision is LIVE. Unset variables = NOT_CONFIGURED.
- **Public API for the site**: `/api/v1/public/catalog/families?channel=
  WEBSITE&language=XX&strictLanguage=true`, product translations and
  content endpoints. Variant `textSources` carry a source language per
  field; `color`/`size` appear only when the variant has a value - the
  website treats them as optional. `size` is the language-neutral Maat, so
  its source is always the requested language.
- **Migration log**: `docs/migrations/2026-08-21/category-revision-
  postgresql.sql` was executed on the Railway Postgres on 2026-08-21 via
  the TCP proxy (3 categories backfilled to revision 0, description
  widened to 4000). Hibernate `update` cannot add NOT NULL columns to
  tables with rows - future primitive columns need the same treatment.
- **Testing lessons**: config fields on an injected bean are written on a
  CDI client proxy - use `ClientProxy.unwrap` in tests; resource classes
  carry `@RolesAllowed`, so direct calls need `@TestSecurity`
  (quarkus-test-security); the rebuild singleton row survives test
  classes (scheduler commits) - clean it in a committed transaction.
  Qute's test-mode `RenderedResults` recorder is switched off in
  `src/test/resources/application.properties`: it kept every rendered
  template for the whole run, and brochure HTML inlines its images (~77
  million characters), which exhausted the heap. `mvn test` needs no
  heap flags.

### Mail
- Production sends via **Brevo HTTPS API** (`BREVO_API_KEY`); Railway
  blocks outbound SMTP below the Pro plan, so SMTP settings exist only as
  fallback for other hosts. Dev uses the Quarkus mock mailer.
- The Brevo key is injected as `Optional<String>` - an empty env var must
  not fail startup (SmallRye reads "" as null).
- Quote mail is fully translated; the button says "sign the quotation
  digitally" in the customer's language; signature block with the text
  wordmark (image blocking cannot strip it) and T/M/W contact lines.
- Internal notifications (customer responded in the portal) must never
  block the customer's action: failures are logged, not thrown.

### Market data
- `FreightRate` log: forwarder quotes per route entered by hand, plus a
  weekly **Drewry WCI** (Shanghai→Rotterdam, USD/40ft) scraped lazily
  from their public page. Scrapes are fail-soft: one 4-second attempt,
  every failure serves the cache; a changed page must never break the
  dashboard. FX rates come from the ECB via the frontend, not here.

## Deployment (Railway)

Dockerfile: maven:3-eclipse-temurin-25 build → eclipse-temurin:25-jre,
Quarkus fast-jar. `railway.json` healthcheck: `/api/public/terms`.
Service env: PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE (from the
Postgres service), `CORS_ORIGINS` + `PORTAL_BASE_URL` =
https://enrosed-erp-frontend.vercel.app, `BREVO_API_KEY`, optional SMTP_*.
Public domain: enrosed-erp-backend-production.up.railway.app.

## Gotchas learned the hard way

- `@ConfigProperty String` + `${VAR:}` crashes startup when VAR is unset;
  inject `Optional<String>`.
- Qute templates call record accessors reflectively - grep templates too
  before declaring code dead.
- SalesEntities holds several entities in one file; pattern-matching an
  edit can silently hit the wrong entity - target the exact class.
- Tests are green at 19; the DocumentText parity test is the guard rail
  when touching i18n CSVs.
