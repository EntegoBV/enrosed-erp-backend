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
- **Portal link = a quotation that was sent.** The portal shows prices, a
  priced PDF and the price list, so a token only opens a document staff
  sent (`sentAt` set; `QuoteService.send` stores token and `sentAt` in one
  save, and nothing else may make a token). `QuoteService.cancel` with
  "Klant verwittigen": a sent quote is mailed with its link and the link
  shows it as cancelled; a request that never went out (website request,
  untaken web order) is mailed without a link and gets no token.
  `SalesLifecycle.neverSent` (CONCEPT or GEANNULEERD without `sentAt`)
  makes `QuoteService.byToken` answer the same 404 as an unknown token on
  every `/api/portal/{token}` route; this also kills the tokens the
  cancellation mail used to make for such requests. A reopened sent quote
  keeps the "being updated" refusal. The link belongs to the customer the
  quote was sent to: `send` only keeps a token when `sentAt` is set (a
  first sending always makes its own), `SalesOrderService.update` drops the
  token when the customer changes, and `cancel` never makes one.
- **Cancelled as an unsent draft shows nothing** (2026-10-08). There is no
  stored copy of what was sent, so a sent quote that staff reopened (or an
  adopted proposal: CONCEPT with `sentAt`) and cancelled before sending it
  again holds edits the customer never got. From that cancel every
  `/api/portal/{token}` route (page, products, photo, PDF, accept, reject,
  propose, withdraw) answers 409 "Deze offerte is geannuleerd." plus the
  message staff wrote, never a figure; the cancellation mail goes without
  the portal link, `activePortalUrl` is empty (staff PDF, portal-link).
  Staff keep the document in their own API. Also when nothing was edited:
  the rule is the state at the cancel, not a diff. No column: the signal is
  `QuoteService.cancelledAsUnsentDraft`, read from `quote_event` newest
  first - of VERSTUURD / HEROPEND / VOORSTEL_OVERGENOMEN the latest is not
  VERSTUURD. Rows cancelled earlier are read the same way; a row without
  such a step after its last sending stays visible. Whoever adds another
  way back to CONCEPT for a sent quote must record one of those events (or
  extend `BACK_TO_DRAFT`). A quote cancelled as it was sent is unchanged:
  page, PDF and mail link. Both refusals of a customer's link are a
  `PortalRefusal` (a `BusinessRuleException`, mapped in
  `BusinessRuleMapper`): 409 `{status, code, message, language, timestamp}`
  with `code` `QUOTE_BEING_UPDATED` (reopened or adopted, not sent again;
  also the accept of a website order whose mailed terms changed, there
  without `language`) or `QUOTE_CANCELLED` (cancelled as an unsent draft).
  `message` is the Dutch sentence as before; `QUOTE_CANCELLED` adds
  `cancellationMessage`, what staff wrote exactly as typed, only when
  there is one (the key is absent otherwise, never null); `language` is the
  code on the customer's file (absent when it cannot be read). The ERP
  page `offerte/:token` words both by the code in that language; a 404 has
  no code. Every route answers this as `application/json`: the photo and PDF
  routes catch the refusal of `byToken` themselves (`refusalAsJson`),
  because through the mapper it left as a printed map under
  `application/pdf`. The portal page (frontend) still shows its fixed "link
  no longer valid" text for any failed load, not the message of the
  refusal: showing a 409's message there is a frontend change.
- **The same rule in the customer's account** (`AccountDocuments`, routes
  `/api/v1/public/account/documents`): its own rule is "sent and not a
  concept", which the cancel makes true again. A plain quotation cancelled
  as an unsent draft is in no list, has no detail and no PDF (404), as
  while it was the reopened draft - also for the customer it was relinked
  to. A website order of the customer stays listed as CANCELLED, shown
  `AS_ORDERED` from its snapshot with the staff message, `hasPdf` false and
  no PDF.
- **Customer answers need an open quote**: accept, reject, propose and
  withdraw all pass `requireOpen` (open status, not past `validUntil`).
  Withdraw used to skip it and put a cancelled quote back on BEKEKEN.
  `cancel` closes a proposal that still lies open as AFGEWEZEN with a
  VOORSTEL_AFGEWEZEN event, because `requireReopenable` refuses a reopen
  while one is pending and handling it afterwards would move the status.
  Staff handling a proposal obey the same: `rejectRevision` only puts a
  quote back on VERZONDEN from WIJZIGING_GEVRAAGD, on any other status it
  closes the proposal and leaves the document alone; `approveRevision`
  refuses on GEANNULEERD, GEACCEPTEERD, AFGEWEZEN and BETAALD. That covers
  proposals the cancel of before 2026-10-08 left open and ones the customer
  left open when signing or refusing (accept and reject do not close them).
  `cancellationMessage` reads the newest GEANNULEERD event.
- `quote_event.detail` is varchar(4000): `EventAdapter.add` cuts a longer
  detail instead of failing the action that records it.
- **Revisions**: the customer proposes quantity changes in the portal. We
  answer with Wijzigen (take over, then adjust), Overnemen (take over as
  asked) or Afwijzen. The customer only sees "verwerkt" after we actually
  RE-SEND the quote - never automatically ("honest revision status").
- **The bell** (`NotificationService.feed()`, `GET /api/notifications`) is
  computed from the documents, no table. Its action items are the ERP's one
  set of "customers waiting": WEBSITE_AANVRAAG, LEVERTERMIJN, VRACHT,
  VOORSTEL and, since 2026-10-16, OPNIEUW_VERSTUREN ("Wijziging nog
  versturen": an adopted proposal not sent again, `at` = the latest
  adoption). That last one is the sales list's `awaitingResend`, read
  through the same static `QuoteService.awaitsResendSince`, and it replaces
  the website item of the same document: one item per document. The ERP
  badge, the Verkoop count and the home's "Klanten wachten" all read
  `actionCount`.
- Delivery terms and freight are three-state: TE_BEPALEN / AANGEVULD /
  known. A second send whose news is the filled-in delivery term says so
  in the mail subject instead of looking like a duplicate mail.
- Pricing: markup modes, country discount tiers, optional extra discount
  with a label that prints verbatim, VAT treatment per customer
  (intracommunautair verlegd prints the legal mention), freight per pallet
  by destination country, minimum order value warning.
- Quantities round up to each product's **order step** ("Verkocht per": full
  carton, display or single piece; see "Sales units" below). The editor
  shows the notice at once and prices a typed quantity after a 5-second
  pause or on blur/Enter; the server rounds again on save (shared rule with
  the portal and the website).
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
- Status: CONCEPT → BESTELD → ONDERWEG → ONTVANGEN; the screens call
  ONDERWEG "Vertrokken" (the enum name stays). Leaving CONCEPT snapshots
  `orderedQuantity` per line so short shipments stay visible; arrival
  books stock. `shippedOn` is server-owned: set to today (Brussels) the
  first time the status says ONDERWEG, then kept; the form value is
  ignored.
- Step back (2026-10-14), for a status set by mistake: the same
  `PUT /purchase-orders/{id}` with `?stepBack=true` (service
  `update(id, order, true)`) accepts an earlier status
  (`requireAllowedTransition`/`isStepBack`): ONDERWEG → BESTELD,
  ONDERWEG → CONCEPT, BESTELD → CONCEPT. Only the ERP "Status
  terugzetten" sheet sends the flag. Every form save carries the status
  its screen loaded and the PUT has no version check, so the flag and the
  move must agree: an earlier status without it (an outdated tab after a
  departure tapped elsewhere) or a later one with it is refused
  untouched with "Deze container staat intussen op <Vertrokken|...>;
  laad hem opnieuw voor je opslaat." The same status with the flag is a
  plain save. ONTVANGEN never goes back
  ("Een ontvangen container gaat niet terug: de voorraad is geboekt.").
  Back to CONCEPT is refused while money moved - any payment row (any
  payee, offsets included) or a legacy `paidTotalEur` ("Terug naar
  Concept kan niet: er zijn al betalingen geboekt op deze container.
  Besteld is de laagste status die nog kan.") - or a supplier credit
  exists ("... er staat een tegoed van de leverancier op deze
  container. ..."); BESTELD is always reachable from ONDERWEG. Any step
  back clears `shippedOn` (a later real departure sets its own day);
  tracking, B/L, documents, payments and credits stay, paid departure
  terms stay paid (early), to CONCEPT drops the ordered snapshot.
  Activity `STATUS_CHANGED` "Status teruggezet van Vertrokken naar
  Besteld" (labels Concept/Besteld/Vertrokken/Ontvangen) with a
  "Vertrokken op" change (dd/MM/yyyy, also logged on a forward
  departure); no push. `requireForwardTransition` stays forward-only and
  `receive()` keeps using it, so the receipt never steps back and a
  CONCEPT container cannot be received directly.
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
  (pieces per carton drives shipping, and the rounding of products sold per
  full carton), "Verkocht per" (order step), barcodes (piece EAN-13 + outer
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

### What each place really shows (2026-10-12)
- The photo overview (and so the answers of `PUT photo-roles` and
  `POST photos/promote`) also carries `places`: eight places, always present,
  each `{shownKey, chosenKey, state, reason, role, scope, relatedProductId,
  relatedLabel, thisColour}`: `websiteColour`, `websiteOpening` (first photo
  in series order, no role, no Hoofdfoto decides it), `documents`,
  `websiteQuote`, `catalogueColour` (brochure colour strip),
  `catalogueDetail`, `catalogueOverview`, `catalogueCompactRow`. State is
  CHOSEN / AUTOMATIC / CHOSEN_NOT_SHOWN / NONE; `reason` (enum
  `ProductPhotoOverviewDto.PlaceReason`) is null only for CHOSEN. The legacy
  fields (`main`, `quote`, `catalogueVariant`, `catalogueOverview`,
  `catalogueDetail`, `photos[].roles`, `visibility`) keep their old values
  and their null-when-automatic contract; never change them.
- Next to it: `otherPhotos` (another colour's own photo a place names; never
  mixed into `photos`), `photos[].chosenFor` (every colour that stored the
  photo as website or catalogue choice), `seriesLiveOnWebsite` (series
  active + PUBLISHED + a website photo + one colour that is active and not
  demo: "the feed carries it", not "the rebuild succeeded"),
  `colourOnWebsite`, `websitePageUrl` (`enrosed.website.base-url` +
  `/nl/products/<publicHandle>/`, null when not live or no handle) and
  `catalogueAssumption`. Rebuild state is not in the overview: the screen
  reads `GET /api/website-rebuild`.
- The three brochure places describe ONE assumed export
  (`catalogueAssumption`): BROCHURE, photos on, one photo per series, every
  colour of the series that is not demo, inactive ones included, by
  `variantPosition, id`. That is the Catalogusstudio default (the export only
  drops demo products). An inactive colour therefore counts for the colour
  strip and its own photos and catalogue choice count; only its series
  photos fall out (not eligible). The website side uses the stricter "active
  and not demo" list. A real export of a subset, the studio's saved order or
  more photos per series can print otherwise.
- A series with one colour in that selection (or a product without a series)
  prints no colour strip: `catalogueColour` is `SINGLE_COLOUR_SERIES` and the
  colour's catalogue choice arrives as `catalogueDetail` AUTOMATIC /
  `THIS_COLOUR_LEAD`. For an automatic `websiteQuote` with reason
  `FIRST_COLOUR_WEBSITE_PHOTO`, `relatedProductId` is the deciding colour
  (first sold colour with a website photo), also for an all-colours photo.
- The selection rules live once, in the pure class `CataloguePhotoPlan`
  (`family`, `variantCandidates`, `compactRow`; no CDI, entities or I/O).
  `PdfCatalogRenderer` prints from it and `ProductPhotoOverviewService`
  describes from it, so the two cannot drift; the overview reads no blob
  ("usable" = storage key + `sizeBytes > 0`). Guards:
  `PdfCatalogPhotoSelectionGoldenTest` (pins what the PDF prints; do not
  edit it to make a change pass), `CataloguePhotoPlanTest`, and
  `CataloguePhotoOverviewParityPersistenceTest` (renders the assumed export
  and compares every printed picture with the overview's `shownKey`).
  `CatalogExportService.model(Request)` is public for that test.
- `PUT /api/product-families/{id}/catalogue-photos` checks a series choice
  against `CataloguePhotoChoices.available` only when the id differs from
  the stored one, so "Formaat" (or the other choice) can be saved while a
  stored choice is no longer available; that choice stays stored, is not
  printed and shows as CHOSEN_NOT_SHOWN / `CHOICE_NOT_AVAILABLE`.
- The start-up foam backfill is create-only for the seed photo
  (`CatalogFoamPhotoBackfillService.ensurePrimaryPhoto`): an existing
  `catalog-primary-red` series photo is never moved, re-bound or
  re-published, and a missing one is created only for a series without any
  series photo. A seed photo the owner deletes stays deleted. The rest of
  the backfill (key canonicalisation, the "Mixed" bear colour) is unchanged.
- The website revision digest (`WebsiteCatalogRevisionService.category`)
  ends a category block with the term `category-photo` + the first photo's
  id, storage key, width and height, only when the category has a photo
  (pinned digests of photo-less fixtures stay valid). Adding, removing or
  reordering the first category photo now really starts a rebuild; the first
  start after this deploy queues one rebuild on a database with category
  photos.

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

### Verkocht per: the order step (2026-10-14)
- Owner decision: per product, sell per **full outer carton** (CARTON, the
  default; every existing product), **per verpakking** (PACKAGE: a display of
  pieces) or **per stuk** in the product's own unit (UNIT: per stolp, per
  hart, per box, or per display on a display basis). One setting governs ERP
  quotes/invoices, the portal, the anonymous website request and website
  ordering. Same unit price whatever the step; tiers unchanged.
- Stored on `Packaging.orderStep` (`product.packagingorderstep`, a plain
  varchar, null = CARTON; it survives packaging NONE like the unit). PACKAGE
  needs a display or gift box (validator). One rule in
  `catalog/domain/OrderSteps`: CARTON = pieces per carton (at least 1),
  PACKAGE = the display's pieces when it is a DISPLAY sold per PIECE with at
  least 2 pieces, else 1 (a gift box holds one piece), UNIT = 1; rounding is
  `ceil(q / step) * step`, 0 stays 0. Quantities stay in sales units.
- Exemptions as before: partner deals, `MarkupMode.CONTAINER_COST` (every
  credit note) and split allocations never round; the save also skips a
  document being tied to a container. Purchasing never rounds.
- Logistics: a part carton ships, weighs and takes pallet room as one full
  carton (`Carton.cartonsFor`), exactly like partner deals; no pro-rata.
- Each sales line remembers the step it was last rounded to
  (`SalesOrderLine.unitsPerOrderStep`, `sales_order_line.unitsperorderstep`,
  null on older lines = the product's step). The calculator uses it, so a
  later step or carton change never re-prices a sent or paid document. The
  server writes it on save (`roundLinesToOrderSteps`), in the preview, when
  a portal proposal is adopted and for a container quote at customer prices;
  it is never read from the client. Such a container quote keeps the
  container's exact pieces until its first save, priced rounded to the step
  meanwhile (as before; not redesigned).
- Wire: `ProductDto.packaging.orderStep` (enum, null on PUT keeps the stored
  value) and `unitsPerOrderStep`; `PricedOrder.Line.orderStep` +
  `unitsPerOrderStep` (the step applied to this line, 1 on exempt documents);
  the same pair on portal lines/catalogue, the split line choice, public
  `VariantDto`, `ProductPrice` and `LineEstimate` (null when a carton step's
  content is unknown). While a quote is open, its portal lines carry the
  step a proposal rounds to (today's, as the portal catalogue), not the
  remembered one. A split refusal names the multiple the line was rounded to.
  The packing slip picks what each line is charged, so it matches the
  invoice; the last carton of a per-stuk line may be part-filled. The Excel/CSV exchange has `verkocht_per` (omdoos,
  verpakking, stuk) as the last column. Only a step other than CARTON enters
  the website revision hash.
- Website: `/configuration` keeps `quantityBasis: FULL_CARTONS` (the live site
  refuses anything else) and adds `quantitySteps: PER_PRODUCT`. An item sends
  `cartons` (old site, carton products) or `quantity` in sales units (rounded
  to the step; a carton product with unknown content refuses it), never both.
  A quantity item is priced without carton content; freight still needs it.
  `WebOrderSnapshot` lines freeze orderStep, unit and basis; account lines,
  mails ("3 stolpen") and the staff summary ("3 → 5 stolpen") word non-carton
  lines in sales units, never "losse dozen" (that means loose cartons).
  Changing a step on an open web order can show a quantity difference.
- Rollout: no production product leaves CARTON until the website that sends
  `quantity` is live.
- Wording (2026-10-15): the ERP calls the step **Klant bestelt per** and the
  basis **Prijs geldt per**. The activity log follows: "Prijs geldt per"
  reads "Display van 12" / "Eén diamant" (logged only when the basis itself
  changes), "Klant bestelt per" reads "Volle omdoos", "Per display"
  (PACKAGE; "Per geschenkverpakking" for a gift box) or "Per diamant". The
  Excel/CSV column stays `verkocht_per` (omdoos, verpakking, stuk), and so
  do the validator's words for it.

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
- **Public colour text** (2026-10-08, `PublicColourText`): a colour text of
  the product's own in the requested language wins; without one a standard
  pick-list colour (`ColourNames.isStandard`, same normalisation as
  `translate`) prints the dictionary word (`ColourNames.standardName`, the
  dictionary's spelling in Dutch too) and is exact in that language, like a
  unit; any other colour borrows from the fallback chain or prints as typed
  and stays a localization hole. The strict family endpoint, the generated
  alt and `PublicLocalizationCompletenessService` (both channels) share the
  rule, so a new product with a pick-list colour never blocks the website
  build. The website revision adds a `dictionaryColour` term only for a
  variant whose colour comes from the dictionary (pinned digest in
  `WebsiteCatalogRevisionServiceTest`; correcting colour-names.csv changes
  it on purpose). Documents and the legacy public catalogue
  (`Product.colourResolved`) print what they always printed - own or
  borrowed product text first, then the dictionary, Dutch as stored - and
  only count a pick-list colour without any product text as exact, so the
  strict PDF check no longer reports it.
- **Website price switch**: `PUT /api/website/quote-settings`
  `{"pricesVisible":bool}` (table `website_quote_settings`, one row; a
  missing row = visible) withholds prices on the whole public website, not
  only the quote flow. While false no amount leaves an anonymous endpoint:
  the quote endpoints redact through `PublicQuotePriceVisibility`, and
  `PublicCatalogPriceVisibility` nulls `variants[].publicPrice` in
  `/api/v1/public/catalog/families` and `products[].salesPriceEur` in the
  legacy `/api/v1/public/catalog`, for every channel. The families payload
  carries top-level `pricesVisible` so the build knows null is on purpose.
  Redaction happens at the HTTP boundary only: ERP calculations, staff
  endpoints and the per-quote portal keep their prices (the portal only
  opens a quotation that was sent, see Sales / quotes). The hidden state
  adds one term to the catalogue revision (the visible digest is unchanged)
  and a changed value queues the website rebuild in the same transaction;
  the static pages follow after the debounce and the Vercel build. Only
  price fields are redacted: `siteCopy` passes through as staff wrote it,
  including policy amounts in running text (the private-label minimum
  order value in `home.faq.*`, the statutory recovery fee in
  `legal.trade.*`). A backend that boots with the row already false has a
  new revision, so `WebsiteRebuildService.onStart` queues one build. The
  entity and its reader `WebsitePriceVisibility` live in catalog; sales
  owns the write side (`WebsiteQuoteSettingsService`).
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

### Customer logins for the website quote request (2026-10-05)
- **What it is**: a website visitor asks for a login, staff approve it in
  the ERP (Login-aanvragen), the customer gets a one-time link by mail,
  chooses a password and is logged in. A logged-in customer sees the
  public price list in the quote request even while the website price
  switch is off, and the request lands on the existing customer. Package
  `be.enrosed.account`; tables `customer_login_request`,
  `customer_account`, `customer_account_token`, `customer_session`
  (`docs/migrations/2026-10-05/customer-account-postgresql.sql`; no
  foreign keys, status columns are plain strings).
- **Credentials**: the customer session travels as `Authorization: Bearer
  ecs1_...`, never as HTTP Basic (that is staff; a non-staff Basic header
  is refused before a `@PermitAll` resource runs). Tokens are stored as
  SHA-256 only. `CustomerSessionGuard.require` returns a session or throws
  401 `SESSION_INVALID`; it never answers "anonymous". Passwords: bcrypt
  cost 12 behind a semaphore of four; staff never see or set one.
- **Public endpoints** (`/api/v1/public/account`): `requests` (ask for a
  login), `link-requests` (new password link), `session` (POST log in, GET
  restore), `session/logout`, `activation/inspect`, `activation`, and the
  priced quote endpoints `quotes/configuration`, `quotes/preview`,
  `quotes/requests` (`CustomerQuoteResource`). Form token purpose
  `ACCOUNT`; one solved challenge is good for one call on the three
  account forms together (`ACCOUNT_CHALLENGE` bucket). Request and
  new-link forms always answer 202 with the same shape; login has one 401
  body for every cause.
- **Staff endpoints**: `/api/login-requests` (list, `/summary`, `/{id}`,
  `/{id}/approve`, `/{id}/reject`) and `/api/customer-logins` (list per
  customer, give, `/{id}/invitation`, `/{id}/withdraw`). The service
  method is transactional and never mails; the resource delivers the
  invitation only after the commit, so a rollback cannot leave a live
  link in a mailbox. A failed mail is `sent: false` plus `lastLinkError`.
- **Prices for a logged-in customer**: `CustomerQuoteResource` asks the
  guard first in every method and returns `PublicQuoteService` answers
  unredacted with `pricesVisible: true`. It never touches
  `PublicQuotePriceVisibility`, `WebsiteQuoteSettingsService` or
  `WebsitePriceVisibility`; the anonymous `PublicQuoteResource` never
  reads a credential. Price basis is the public list for everyone, a
  partner customer included (no landed cost, no customer prices).
- **Logged-in quote** (`PublicQuoteService.submitForCustomer`): company
  name, company country, e-mail and VAT number of the body are ignored;
  no customer is created or changed; the estimate uses the customer
  record's VAT number. The internal note gets `Aangevraagd via klantlogin
  <e-mail>` and, when they differ from the record, `Contact op aanvraag:
  ...` with every `[` and `]` removed (bracket markers in that note are
  read by staff and code). Stored answers live under idempotency purpose
  `ACCOUNT_QUOTE`, apart from the anonymous ones.
- **Tick box in the anonymous quote** (`SubmitRequest.loginRequested`,
  part of the fingerprint): the internal note gets a last line starting
  with `[LOGIN_AANVRAAG]` and `WebsiteQuoteLoginRequested` is fired.
  `LoginRequestService` observes it AFTER_SUCCESS and only enqueues work:
  a login request can never roll back the quote, and a rolled-back quote
  leaves none. The quote's team mail gains the fact `Login gevraagd` only
  when a note LINE starts with the marker; there is no second push or
  mail for that request.
- **One request per e-mail, first wins**: a later request with other
  details is appended to `later_submissions` (at most five, never
  overwritten). Ceilings: 300 open requests per route, 6 notices per hour
  and 20 per day; beyond those a request is stored silently or, for a
  full route, answered as received but not stored.
- **Background work**: `LoginRequestNotifier.runLater` (common pool, no
  outbox, no `@Scheduled` job). Those threads have no request context and
  no transaction, so every database access there opens its own
  (`REQUIRES_NEW` or `QuarkusTransaction.requiringNew()`).
  `AccountHousekeeping.purge()` does the retention after a login, an
  approval, a rejection and a stored request; never inside a transaction.
- **Mail**: one customer mail, the invitation (`account-invite-mail.html`,
  thirteen `mailAccountInvite*` rows), without office blind copy; the link
  is `<WEBSITE_BASE_URL>/[<code>/]account/#activate=<token>` and its
  lifetime is the single key `enrosed.customer-account.invite-ttl-days`.
  Raw tokens, links and passwords are never logged or returned; the
  mailer refuses in mock mode outside dev and test.
- **Customer deletion** removes the customer's logins, sessions, links
  and login requests (`CustomerDeletionListener`).
- **Tests**: set the notifier's executor with `useExecutor` (a field
  written on the CDI proxy does nothing); the notice buckets and rate
  buckets are committed and shared by the whole run, so use unique
  e-mails and clear the notice buckets where a notice is expected.

### Website orders of logged-in customers (2026-10-08)
- **What it is**: a logged-in customer places an ORDER on the website
  quote page; Enrosed confirms it. Anonymous visitors keep the quote
  request. An order is a concept OFFERTE of channel WEBSITE from the
  ordinary quote series PLUS a row in `sales_web_order` (the order state)
  PLUS a row in `sales_order_delivery` (address and contact typed for this
  order), all with the same id
  (`docs/migrations/2026-10-08/sales-web-order-postgresql.sql`). **The
  `sales_web_order` row alone defines a "websitebestelling"**; the note
  marker and the channel are never the key. A website concept without the
  row is a legacy "Websiteaanvraag". No new status, document type or
  number series, and no field on `SalesOrder`.
- **Kill switch**: `enrosed.website.ordering.enabled`
  (`WEBSITE_ORDERING_ENABLED`, default true), read on every call. Off:
  every endpoint under `/api/v1/public/account/documents` and `/orders`
  answers 404 `NOT_FOUND` after the session guard (what an older backend
  answers; the website then offers the quote request) and the old
  logged-in submit drops its minimum rule.
- **Customer endpoints** (`/api/v1/public/account`, bearer session, GET
  and POST only, `Cache-Control: no-store`): `documents/delivery-defaults`
  (prefill and the website's capability probe, own budget), `documents`
  (list `kind=ORDERS|INVOICES`), `documents/{id}`, `documents/{id}/pdf`
  (`CustomerDocumentResource`, `AccountDocuments`); `orders/preview`,
  `orders`, `orders/{id}/changes`, `orders/{id}/cancellation`
  (`CustomerOrderResource`, `WebOrderService`). DTOs are the allow-list
  records of `AccountOrderDtos`. No sentence of the sales core leaves:
  404 `ORDER_NOT_FOUND` (one body for missing, foreign and hidden), 409
  `ORDER_LOCKED`, `ORDER_CHANGED`, `ORDER_CHANGE_LIMIT`,
  `ORDER_REVIEW_REQUIRED`, `DOCUMENT_UNAVAILABLE`.
- **Writes**: guard, switch, replay, budgets, read-only validation, form
  check (the page's QUOTE token), then the idempotency transaction.
  Stored answers live under purpose `ACCOUNT_ORDER` and are the four
  fields of `OrderReceipt` only. Placing uses the budgets of the logged-in
  quote submit; change and cancel have `ACCOUNT_ORDER_WRITE` (20 an hour
  per login). A filled honeypot gets a pretended 201 without id on place
  and 422 on a change.
- **Minimum order value**: an order below the minimum of the delivery
  country is refused with 422 `items: MINIMUM_NOT_MET`, on validation and
  again in the write. Only lines the ERP can price count; a line without a
  price, or a carton line without known carton content, adds nothing. While the switch is
  on, the old logged-in `quotes/requests` holds the same minimum
  (`PublicQuoteService.validateSubmissionForCustomer`); the anonymous
  request never does.
- **Pricing of an order** (`PublicQuoteService.prepareOrder`): the
  customer RECORD delivered at the typed address
  (`Customer.withDeliveryAddress`), VAT from the record. The sales core
  prices the stored document the same way through
  `WebOrderDeliveries.pricingCustomer`, so the estimate equals the stored
  order, its invoice, a split part and a "Nieuwe kopie". The customer
  record is never written by a customer path.
- **Snapshot**: `sales_web_order.order_snapshot` freezes the ERP's own
  pricing of the saved document at placement and at every customer change
  (`WebOrderSnapshot`); `ordered_terms` is its fingerprint
  (`WebOrderTerms`), null when freight, a price or carton content was
  still open. The customer keeps seeing the snapshot until the order was
  sent, so unsent staff edits never show under "my orders".
- **Customer change**: until staff take the order the customer replaces
  it whole (`SalesOrderService.updateByCustomer`). A product that stays
  keeps its line id, its unit price and its cost; a new product gets
  today's price; order date and validity stay. `revision` counts the
  customer's versions (cap 20). The Dutch change summary
  (`customer_change_summary`) goes to the team mail, the push, the history
  and the ERP banner and names a kept price that is no longer the list
  price.
- **Locks**: form stripes, then the `sales_order` row, then the
  `sales_web_order` row. Every writer locks first and reads afterwards.
  Customer paths call `lockDocumentForCustomer`, `updateByCustomer` and
  `QuoteService.cancelByCustomer`; they never run the staff gate and
  never infer "customer" from a missing staff identity.
- **Staff gate** (`WebOrders.afterStaffLock`, inside
  `lockDocumentForMutation`): every staff mutation carries
  `?webOrderRevision=<n>` (`WebOrderRevisionFilter`). A stale revision, or
  none for an order the customer ever changed, is refused with 409
  `WEB_ORDER_CHANGED`, also after a colleague took the order. The first
  staff mutation that passes takes the order "in verwerking" (trigger
  `KNOP` for `POST /{id}/take-into-processing`, else `AUTOMATISCH`); the
  marker is never cleared and a rolled-back action leaves it unset. The
  SYSTEM actor never takes an order.
- **D1 guard** (all in `WebOrders`): an invoice is made, and issued, only
  when price, quantity, freight, extra lines and VAT rate equal what the
  customer ordered, or what the customer approved in the version that was
  mailed (`requireInvoiceable`, `requireIssuable`). An approval through
  the quote link counts only for the version sent (`requireAcceptable`).
  No split and no partner deal on a web order before approval; no delete
  unless cancelled or declined; no reopen of a customer-cancelled order.
  There is no override; soften a rule there and nowhere else.
- **Mails**: order received and order in processing
  (`order-mail.html`, `WebOrderMails`, observers after the commit, the
  timestamp claimed with one conditional update, no job and no outbox).
  The approval mail and the staff cancellation mail of a web order get
  order wording. Every customer mail about a web order goes to the login
  that last placed or changed it (`WebOrderRecipients`), the approval and
  cancellation mail with the record's address in copy. A failed mail shows
  on the order with "Opnieuw sturen" (`POST /{id}/web-order/mails`).
- **Customer address for the invoice ("Leveradres overnemen")**: a
  customer made at login approval has no street, postal code or city, and
  `validateInvoiceForSend` refuses an invoice or credit note without them.
  `CustomerInvoiceData` (pure, one place) names what is missing, writes
  the refusal ("De factuur kan niet uitgereikt worden: bij klant X
  ontbreken straat en nummer, postcode en stad. Vul dit in bij de
  klantgegevens.", HTTP 409 without a code, as before; "verstuurd" instead
  of "uitgereikt" when the invoice is already issued and the record was
  emptied afterwards - such a document shows no notice) and decides the
  offer. The staff view of ONE document (never the list) carries
  `invoiceCustomer` {customerId, company, missing[ADDRESS|POSTAL_CODE|CITY],
  takeover{address,postalCode,city,countryCode}|null,
  takeoverBlockedBy PICKUP|NO_DELIVERY|OTHER_COUNTRY|INCOMPLETE|null};
  null when nothing is missing, or the document is archived, cancelled,
  declined, expired, customer-cancelled, or an invoice/credit note past
  concept. The takeover is offered only when the document's delivery row
  (`WebOrderDeliveries.forDocument`, so also the invoice made from the
  order) is a DELIVERY that fills every empty field and agrees with every
  field the record already has, in the record's country. `takeover` reads
  as the record will afterwards: a field the record already has in the
  record's own spelling, `countryCode` the record's own or null.
  `POST /api/sales-orders/{id}/customer-address-from-delivery` with the
  address staff confirmed ({address, postalCode, city}) answers the fresh
  view. `CustomerAddressTakeover.take` locks the document with
  `lockDocumentForCustomer` (**never the staff gate**: no webOrderRevision,
  the order is not taken into processing and nobody is mailed), then the
  customer row, and calls `CustomerService.fillMissingAddress`, which
  writes only empty street, postal code and city, **never the country**
  (the delivery row has none; the document's country is staff's to
  change), name, VAT number or contact data, and logs a CUSTOMER UPDATED activity
  "Adres overgenomen van het leveradres van <number>". A complete record
  answers 200 and writes nothing; a delivery that no longer reads as
  confirmed is 409 "Het leveradres is intussen gewijzigd. Controleer het
  adres opnieuw."; no offer is 409 "Het leveradres kan niet overgenomen
  worden. Vul het adres in bij de klant.". The website and the customer
  session cannot reach any of this (`CustomerAddressTakeoverHttpTest`).
- **Account PDF**: `SalesPdfOptions.accountCopy()` renders an invoice or
  credit note as issued, without receipts or settlement; the customer
  history never shows a payment state.
- **Tests**: `WebOrderStaffGateTest.Shop` builds customers, products,
  logins and orders and removes them; `WebOrderFlowHttpTest` walks four
  orders over real HTTP. In a `@QuarkusTest` a read outside a transaction
  answers from the test's own session and can be stale after another
  transaction wrote: read inside `QuarkusTransaction.requiringNew()`.
  Not verified on PostgreSQL locally (none on the build machine): the
  migration, schema validation and the row locks first meet it on the
  Railway TEST deploy.

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
Quarkus fast-jar. The service settings (Dockerfile builder, pre-deploy
migration command, healthcheck `/api/public/terms`, restart policy) are in
`.railway/railway.ts`, which replaced `railway.json`. Railway never reads
that file during a deploy: a setting reaches an environment only through
`railway config apply`, run by hand per environment, and a deploy uses what
was applied last. To change a setting: `railway config plan` and `apply` on
test, push to test, then the same for production. Check `railway status`
first; a checkout can be linked to production. The file declares the service
whole and lists its variables by name (values stay in Railway), so add a new
variable's name there before the next apply, or the plan proposes deleting
it. Setup once: `npm ci` in `.railway/`, with a current Railway CLI (5.44
cannot evaluate the file; checked with 5.63).
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

## Jaarinventaris (2026-10-09)

The yearly stock count and the year-end closing stock with value for the
accountant, under Belgian rules. Module `be.enrosed.inventory`; the lot
cost lives in `be.enrosed.sourcing` because it reads the reconciliation.
Nothing existing changed behaviour: no existing table, endpoint or
scheduled job was touched, and `StockLedger` only gained two readers.

- **Schema**: fourteen additive tables in
  `docs/migrations/2026-10-09/stock-inventory-postgresql.sql`, registered
  after the web-order file. Entities are plain classes with public fields
  in DDL order, IDENTITY ids, every other column nullable, no relations.
  Money has scale 2, unit values 4, the three rates 8, and the migration
  writes the same `numeric(19,x)` so `validate` and `update` end on one
  column type. The two unique
  indexes exist on PostgreSQL only; H2 builds the tables from the
  entities, so the services enforce both rules under row locks.
- **Lot cost** (`LotCostCalculator`, `LotCostService`): the acquisition
  value per piece of a receipt lot (one product on one container).
  Supplier price net of price credits divided by the billed pieces, plus
  the container costs divided by the received pieces. Never the Enrosed
  kost, a margin, recoverable VAT or anything booked under "Bijkomende
  kosten". A foreign payment up to the day ownership passed keeps its
  bank euro, a later one counts at the container rate and the difference
  is listed outside the value. An unpaid amount is taken at the Afspraak
  and marked as estimated until an accrual decision confirms it. A price
  credit lowers the goods of its lot to nothing at most (blocker
  `TEGOED_MEER_DAN_GOEDEREN` for the rest). Credits kept outside the
  value that are together above the cost of the lost pieces block
  (`TEGOED_MEER_DAN_VERLIES`) until every shortage and damage credit has
  the user's own decision; one decision does not clear it.
- **Count** (`StockCountService`, `/api/stock-counts`): one open session
  per location, two phones under the session row lock, a reason for every
  difference. Booking writes one `STOCKTAKE` row per counted line at
  live level + difference, so a sale during the count survives. Before
  it writes, the booking reads every level again from the database with
  the row locked and refuses `TELLING_GEWIJZIGD` when one moved since the
  check; `StockService.write` itself still takes no lock. A
  difference that an unshipped invoice or an unbooked container explains
  is refused until that document is handled or the user confirms.
  A booked count is never undone; a correction session replaces it per
  product.
  A line whose product was deleted is in no list of the booking check
  and in none of the four figures of the session summary, on the count
  page and on the hub alike. Refusals and notices that print a number
  use the singular for 1 (`ClosingNotices.counted`, `pieces`); a refusal
  ends without a full stop, as everywhere in this backend.
- **Closing** (`StockClosingService`, `/api/stock-closings`): one row per
  year and version, `CONCEPT` or `DEFINITIEF`. User input lives only in
  `stock_closing_decision` and `stock_opening_layer`; every other closing
  table is output that a compute deletes and rebuilds. Every write takes
  the closing row with `requireConcept` first. The quantity is the booked
  count rolled to the closing date (`StockRoll`). A ledger row that was
  listed and has left the stock book since stays listed as removed, counts
  for nothing and keeps its place in the chain, so its pieces never move
  onto the row after it; a correction version reads the rows of the
  version it replaces for this. A row that counts for another number than
  it booked, or that was booked at the very instant of a count before the
  closing date, is flagged for review. The value is FIFO per
  receipt lot (`FifoValuer`), a later year carries the frozen layers of
  the previous final closing and never recomputes them. Blockers and
  warnings are `ClosingNotices`; the fingerprint of everything frozen is
  `ClosingHash` (`data_sha256`).
- **Definitief maken** (`StockClosingFinalizer.makeFinal`): locks the
  row, computes once more, refuses `CIJFERS_GEWIJZIGD` when the hash is
  not the one the screen sent and `GEBLOKKEERD` while a blocker stands,
  then copies the company identity, renders the workbook and the PDF from
  the stored rows, stores both through `PhotoStorage` with key, SHA-256
  and size on the closing, and marks the version that was valid until
  then as replaced. A final closing has no write path and no delete
  path; its downloads are the stored bytes.
- **Correction** (`newVersion`): only of the valid final version and only
  while the year has no concept. The decisions are copied in id order
  with who decided and when; the new concept is rebuilt from the data of
  today and `ClosingVersionDiff` lists every product, lot, movement and
  opening value that differs from the stored rows of the version it
  replaces. A later year that was final on the old version is shown, not
  repaired.
- **Files** (`ClosingReportData`, `PdfStockClosingRenderer`,
  `StockClosingWorkbook`): both are built from stored rows only; the one
  live value is the company head of a concept file. The PDF sections and
  their sheets take their tables from the same `ClosingReportData`
  methods, so add a column there and both follow. The files print a
  notice through `ClosingNotices.reportText`, which drops the advice
  sentences of the screen (the `HINT_*` constants). In the PDF a product
  and its lots are one `tbody` that a page cannot split, and the section
  tables have fixed column widths per column kind. Wording rules that the
  tests guard: "Enrosed kost" only as "buiten waarde", "Bijkomende
  kosten" never alone (it is the legal name of the costs that ARE in the
  value), and the word is "waardevermindering", never "afwaardering".
- **Retention**: nothing removes a closing, its rows, its decisions, a
  booked count or the two stored files. A future cleanup that walks
  `photo_blob` without a reference must learn `pdf_storage_key` and
  `xlsx_storage_key` on `stock_closing`.
- **Tests**: `LotCostCalculatorTest`, `StockRollTest` and `FifoValuerTest`
  hold the worked examples to the cent; `InventoryIndependentFlowTest`
  walks count, closing, final, correction and a deleted ledger row over
  HTTP on its own H2 database with hand-calculated figures;
  `StockClosingServiceTest` and
  `StockClosingFlowTest` empty the book inside one rolled-back
  transaction (`quiet()`), book receipts today and date them afterwards,
  and use years in the past. `StockClosingHttpTest` commits a closing for
  2001 and removes it. Not verified on PostgreSQL locally (none on the
  build machine): the migration, the schema validation of the fourteen
  pairs, the two unique indexes and the row locks first meet it on the
  Railway TEST deploy.

## Uitgaven: Aan wie, beurzen en het bedrag incl. btw (2026-10-12)

- **Aan wie** is a stored list (`cost_party`, `/api/cost-parties`,
  `CostPartyService`): the name (unique on `name_key`, the lower-cased,
  space-collapsed name), the defaults a cost to that party usually takes
  (category, VAT rate, channel or fair) and the IBAN (stored without
  spaces, upper-case, shape-checked); no notes. The bookings teach it:
  creating or updating a company cost or a recurring cost, and recording
  an OUTGOING bank movement, call `learn(name, category, vat, channel)`
  after their own `recordActivity`, which adds an unknown name with that
  booking's defaults and never changes a known one (incoming money comes
  from customers, who are no payees). The channel is taught ONLY when the
  category is BEURS, else null: a rent paid under WEBSITE says nothing
  about the landlord. `learn()` is fail-safe: `REQUIRES_NEW`, returns
  early for a blank name or one over 200 characters, catches the
  unique-violation race of two first bookings of one name, and every hook
  wraps it in try/catch, so the list can never cost the user the booking.
  Renaming a party renames the string on company costs, recurring costs
  and bank lines by the same key the list uses (the distinct stored
  spellings are matched in Java in `PanacheCostParties.renameEverywhere`,
  then updated by id, so 'Immo  Ham' follows 'Immo Ham'); the activity
  reads 'Aan wie hernoemd: X → Y · n kosten · m vaste kosten · k
  bankbewegingen'. Deleting one never touches a booking. The three
  services reach the learner through `Instance<CostPartyService>` behind
  an `isResolvable()` guard, so the Mockito tests that build them by hand
  keep working.
- **Beurzen** (`fair`, `/api/fairs`, `FairService`): a fair is a sales
  channel code the costs share with the sales side, so a cost on it lands
  under "Per kanaal"; the cost keeps it in its existing `salesChannel`.
  The code is derived from the label once (diacritics stripped, upper-case
  ASCII, other runs become `_`, at most 40: "Trends & Trade Ede" becomes
  `TRENDS_TRADE_EDE`), may not be DIRECT, WEBSITE, PARTNER or FAIR, and
  never changes; a fair is switched off rather than deleted while a
  company cost, a recurring cost or a sales document carries its code
  (`countBySalesChannel` on `CompanyCosts`, `RecurringCosts` and
  `SalesRepositories.Orders`, injected into `FairService`). `FairSeed`
  runs at every start-up without effect the second time: it inserts TICA
  and EDE when missing, moves the costs and recurring costs on the retired
  category TICA to BEURS with TICA as their fair (unless they named a
  channel), and backfills `cost_party` from every distinct party of the
  costs and recurring costs and every outgoing bank counterparty, with the
  newest booking's category and VAT and the channel only for a BEURS
  booking. The PostgreSQL migration does the same three things in SQL
  (`on conflict (code) do nothing`, the four `update` statements, and
  `insert into cost_party … select distinct on (name_key) … on conflict
  (name_key) do nothing`), so dev H2 and Railway agree.
- **Bedrag incl. btw**: `CompanyCost.amountInclEur` is the inclusive total
  as it was typed in and null for a cost entered excl.
  (`company_cost.amount_incl_eur`, nullable). `vatEur()` is incl − excl
  when a total is stored, else excl × rate; `totalInclEur()` is the entered
  total or excl + VAT. The record accessor `amountInclEur()` deliberately
  stays the raw value: Jackson serializes a record through its accessors,
  so the JSON tells the screen whether the cost was entered incl. (a
  number) or excl. (null). The service scales the total to cents, refuses
  one below excl., and requires it to match the rate within two cents
  (equal to excl. without VAT). Recurring costs stay excl.-only. Migration
  `docs/migrations/2026-10-12/cost-parties-fairs-and-incl-amount-postgresql.sql`
  is registered in the Dockerfile and the runner.
- **Bankkoppeling**: a bank line carries what it settled through hard links,
  `purchase_payment.bank_statement_line_id` and
  `company_cost.bank_statement_line_id` (nullable, indexed, no foreign key),
  exposed as `bankStatementLineId` on `PurchasePayment`, `PaymentRow` and
  `CompanyCost`. `BankStatementService.ManualRequest` takes the optional
  `purchasePaymentIds` and `companyCostId`; `create()` persists the line and
  then links each payment and the cost in the same transaction, refusing an
  unknown one ("Betaling niet gevonden", "Kost niet gevonden") or one that is
  already on a line ("… is al aan een bankbeweging gekoppeld"), so a refused
  link leaves no line and a payment or a cost is on the bank once; a retried
  `requestId` returns the existing line without linking again. The screen
  creates the line AFTER the payments and the cost, so a failed line never
  leaves an orphan transfer, and one line carries every ledger payment of one
  supplier transfer (goods, CIF freight, inspection). `delete()` clears both
  link columns before the line goes; deleting a payment or a cost leaves the
  line, which is the bank's truth. No service takes the link from a form:
  `CompanyCostService.update()/markPaid()` carry it from the stored cost,
  `PurchaseOrderService.updatePayment()` from the stored payment. The text
  marker (`containerbetaling #id`, `kost #id`) stays in the reference for the
  soft-link display of older lines. Migration
  `docs/migrations/2026-10-12/bank-links-postgresql.sql`, the last script of
  the runner.

## Bank account registry and Odoo authority (2026-10-13)

- `/api/bank-accounts` lists canonical normalized account keys from bank readings,
  statement rows, active sales receipts and cost payments, plus saved metadata.
  Existing `source=ODOO` statement rows immediately make their account authoritative;
  no reimport or metadata write is required. `source`, `archived`, explicit `aliases`,
  `odooJournalId` and `manualMovementsAllowed` tell the frontend how to group the
  overview. Odoo accounts use imported movements as the official ledger; historical
  manual movements and unlinked receipts remain preserved outside that ledger.
- PUT saves a label and explicit aliases. A canonical key and an alias share one
  primary-key namespace in `bank_account`; aliases group old names without rewriting
  any balance or payment. An imported account cannot be downgraded to manual or
  absorbed as another account's alias. No KBC label is automatically merged.
- DELETE archives locally; POST `/{key}/restore` restores the overview entry. Neither
  action deletes financial history, closes a bank account or stops provider imports.
  Manual movement creation is refused on archived and Odoo accounts, including
  explicit aliases. Sales/cost payment registration and allocation remain separate.
- A new Odoo authority claim requires a journal ID, remotely checked against its
  actual EUR bank account. A verified namespace/company/journal binding is retained
  on label-only edits. Sync checks the binding before reading statement rows and
  fails closed if the provider account or configuration changes. No company IBAN
  or journal ID is hardcoded. Existing provenance permits display/alias edits while
  the optional journal mapping remains unset. An explicit sync registers verified
  EUR journal bindings from actual provider account numbers, preserving existing
  labels, aliases and archive flags. Connection checks remain read-only; synthetic
  account-name fallbacks do not create a verified binding.
- Additive migration `docs/migrations/2026-10-13/bank-account-registry-postgresql.sql`
  is copied into the image and runs after `odoo-bank-source-postgresql.sql`.

## Btw-regeling per kost, btw-aangifte, afschrijvingen en deelbetalingen (2026-10-12)

- **Btw-regeling** (`VatRegime`, `company_cost.vat_regime`, plain text, nullable):
  one regime per cost, nine values. `BE` (Belgian invoice, VAT deductible),
  `EU_GOODS` / `EU_SERVICES` / `NON_EU_SERVICES` (reverse charge: we
  self-assess the VAT in 55/56 and deduct it in 59; `reverseCharge()`),
  `FOREIGN_VAT` (foreign VAT on the invoice, a cost here), `EXEMPT`
  (insurance, post, the forwarder's sea freight: base in 82, no VAT),
  `OUT_OF_SCOPE` (bank charges, fines, taxes, wages: not in the return),
  `NO_INVOICE` (a till receipt: the gross amount is cost, no deduction),
  `IMPORT_VAT_PAID` (VAT paid at customs on the import document: a deduction
  with no base, `amountExclEur` 0 and `amountInclEur` = the VAT). There is no
  `IMPORT_DEFERRED` regime: the ET 14000 licence is a company setting
  (`company_profile.import_vat_deferred`, `CompanyProfile.importVatDeferred()`
  reads null as false) that changes the container path only. A forwarder
  invoice with exempt freight, 21 % fees and advanced import VAT is booked as
  separate costs; no line-level VAT. `vatRegimeConfirmed` (`Boolean`, read
  through `Boolean.TRUE.equals`) is false for a regime the backfill or the
  supplier's country derived; `supplierCountry` is the ISO code; `costKind`
  (`GOODS` → 81, `SERVICES` → 82, `INVESTMENT` → 83; null reads as services,
  goods for MATERIAAL on the frontend); `expenseType` is the deductibility
  preset (`STANDARD` 100/100, `RESTAURANT` 0/69, `RECEPTION` 0/50, `GIFT`
  100/50, `CAR` 50/100, `MIXED` 75/100, `CUSTOM` keeps the typed
  `vatDeductiblePct` / `taxDeductiblePct`, both 0–100, null reads 100);
  `depreciationYears` 1–50 and `depreciationStart` (default the cost date)
  only for an investment, nulled otherwise. The quarter of a cost is the
  quarter of its `date`; a cost created after the quarter's filing date (the
  25th of the month after) is badged 'na indiening' on screen, nothing moves.
- **Money, once in Java and mirrored in `finance-metrics.ts`** (`CompanyCost`,
  cents HALF_UP): `vatEur()` = BE/FOREIGN_VAT/null → incl − excl when a total
  is stored, else excl × rate; reverse charge → excl × rate (the stored incl is
  nulled on save); IMPORT_VAT_PAID → incl; the rest 0. `totalInclEur()` (what
  leaves the bank) = BE/FOREIGN_VAT/null → incl ?? excl + VAT; reverse charge,
  EXEMPT, OUT_OF_SCOPE, NO_INVOICE → excl; IMPORT_VAT_PAID → incl.
  `returnVatEur()` (what the return shows) = BE, reverse charge,
  IMPORT_VAT_PAID → `vatEur()`, else 0. `deductibleVatEur()` (grid 59) =
  return VAT × `vatDeductiblePct` (import VAT always 100 %);
  `nonDeductibleVatEur()` = the rest, which is cost and joins the base in
  81/82/83 (Notice §161). `expenseEur()` (what the result analysis and the
  cost totals count) = BE and reverse charge → excl + non-deductible VAT;
  FOREIGN_VAT → the cash total; EXEMPT, OUT_OF_SCOPE, NO_INVOICE → excl;
  IMPORT_VAT_PAID → 0 (own line 'Btw voorgeschoten op invoer').
  `taxDeductiblePct` is reported and never changes a total. A null regime
  (rows from before, unknown) pays like a Belgian invoice and is 'Nog te
  bepalen' for the return.
- **Validation** (`CompanyCostService.regimeChecked`, shared with the
  recurring definitions): the regime is required on create ('Kies de
  btw-regeling'); on an update a body without one keeps the stored regime
  and its confirmed flag (legacy rows). The country is two letters; BE forces
  'BE'; EU_GOODS/EU_SERVICES need an EU member other than Belgium (XI counts
  for goods only); NON_EU_SERVICES a country outside the EU; FOREIGN_VAT a
  country other than Belgium. Reverse charge needs a rate above zero and
  stores no inclusive total; EXEMPT/OUT_OF_SCOPE/NO_INVOICE refuse a rate or
  a total above excl. ('Deze regeling draagt geen btw') and store null for
  both; IMPORT_VAT_PAID needs excl 0 and a positive total, deductibility
  forced 100/100; BE/FOREIGN_VAT check the total against the rate as in round
  one. A preset other than CUSTOM overwrites the two percentages.
- **Betalingen** (`cost_payment`, `CostPayment`, `CostPayments` port,
  `PanacheCostPayments`; no foreign key, the service deletes the payments
  before the cost): several payments on one cost (a KBC invoice paid by
  several transfers). `paidOn` is derived, never typed: `totalsOf(cost,
  payments)` reads paid = Σ amounts, paid once Σ ≥ cash total − € 0,01 with
  `paidOn` = the last payment date (else null), open = cash total − paid and
  0.00 once paid (a cost settled a cent short never reads 'Deels' next to
  'Betaald'); overpayment is allowed ('Te veel betaald'). A cost from before the
  rows with `paidOn` and no payment rows reads as paid in full on that day
  (the dev H2 database has no migration runner; the PostgreSQL backfill
  writes those rows). `create()` with `paidOn` writes one full payment
  ('Betaald gezet'); `update()` with `paidOn` and no rows writes it (carrying
  the cost's bank link), with exactly one row not on the bank moves that
  row's date (and its amount when it was the full payment and the cash total
  changed), with other rows ignores the form's date; `paidOn` null with rows
  is refused only on a settled cost ('Verwijder eerst de betalingen'): a cost
  the rows do not cover carries no derived date, so the view's null date is
  no change and the rows decide (the KBC invoice stays editable and its
  regime confirmable while the transfers come in). `markPaid` pays the
  remainder ('Deze kost is al volledig betaald' at 0). `addPayment` / `updatePayment` /
  `deletePayment` (`POST/PUT/DELETE /api/costs/{id}/payments[/{paymentId}]`)
  return the `CostView`; an amount above zero in cents, a day not in the
  future, account ≤ 120, note ≤ 300; a payment on the bank keeps its amount
  and date ('Deze betaling staat op de bank; maak de bankbeweging eerst los');
  deleting a banked payment is allowed (the line stays, the link goes; a line
  that carries other payments too must be unlinked first).
  Deleting the last payment reopens the cost (`paidOn` null, `openEur` = the
  cash total; the legacy "paid without rows" reading is for rows that never
  existed, not for rows taken away), and a deleted payment takes
  `company_cost.bank_statement_line_id` with it when that was its line.
  `RecurringCostService.bookDue` writes one payment 'Domiciliëring' (actor
  'systeem') for an `autoPaid` definition.
- **CostView JSON** (every `/api/costs` response): the `CompanyCost` fields at
  the top level (`@JsonUnwrapped`) plus `payments` (each `{id, companyCostId,
  paidOn, amountEur, account, bankStatementLineId, note, recordedAt, actor}`),
  `paidEur`, `openEur`, `cashTotalEur`. `CompanyCost` is
  `@JsonIgnoreProperties(ignoreUnknown = true)`, so a PUT of the view is
  accepted; the service rebuilds the money from the stored cost.
- **Bank**: a payment links to its bank line (`cost_payment.bank_statement_line_id`);
  `BankStatementService.ManualRequest.costPaymentId` names it and `link()`
  sets it (refusing an unknown or already linked payment), also setting
  `company_cost.bank_statement_line_id` when that payment is the cost's only
  one (the link of a one-shot full payment; `companyCostId` is ignored when
  `costPaymentId` is present). A `companyCostId` alone resolves to the cost's
  single unlinked payment, to the cost itself when it has no rows (legacy),
  and refuses several open payments ('Kies de betaling die deze bankbeweging
  dekt'). `delete()` clears the link on the payments too. The frontend marker
  is `kostbetaling #id`.
- **Aan wie** learns `country`, `vatNumber` (upper-case, no spaces or dots,
  `^BE[01]\d{9}$` for Belgian numbers, `^[A-Z]{2}[A-Z0-9]{2,16}$` otherwise,
  'Dit is geen geldig btw-nummer') and `vatRegime`.
  `learn(name, category, vatPct, channel, country, regime)` gets the regime
  only from a confirmed cost; the one exception to never-update: a known
  party without a regime takes the regime (and, when it has none, the
  country) of a confirmed booking, fill-only, nothing else changes.
- **Recurring definitions** carry `vatRegime`, `vatRegimeConfirmed`,
  `supplierCountry`, `costKind`, `expenseType`, `vatDeductiblePct`,
  `taxDeductiblePct` (same rules, no investment); a booked cost inherits them
  with `vatRegimeConfirmed = definition.vatRegime() != null &&
  definition.vatRegimeConfirmed()`: a definition saved through the sheet books
  confirmed costs, a backfilled one (BE, unconfirmed) books costs the
  'Btw-regeling bevestigen' item still catches.
- **Containers**: `purchase_order.clearance` (`Clearance`: `BE_CUSTOMS`,
  `NL_FISCAL_REP_TO_BE`, `NL_FISCAL_REP_SOLD_IN_NL`; nullable = 'te
  bevestigen', form-owned like the tracking reference, PUT
  `/api/purchase-orders/{id}` carries it). `Clearance.suggestedFor(port)`
  gives Antwerp or Zeebrugge → BE_CUSTOMS, else NL_FISCAL_REP_SOLD_IN_NL (owner, 2026-10-13: 24/7 Customs clears in the Netherlands and the goods usually stay there, at the transport partner's, until sold); the suggestion is
  shown, never stored by itself. The goods go to 81 at
  `costing.totals.goodsEur` in the quarter of `receivedOn`; a Belgian
  clearance's import VAT enters through an IMPORT_VAT_PAID cost or is
  self-assessed (87/57/59 on `customsValueEur + dutyEur`) when the ET 14000
  setting is on; a Dutch clearance brought to Belgium is an intra-EU
  acquisition (86/55/59 on the same base).
- **Backfill** (`docs/migrations/2026-10-12/vat-regimes-and-cost-payments-postgresql.sql`,
  the last script of the runner, rerunnable): every nullable column above,
  the `cost_payment` table with its two indexes; `vat_regime = 'BE',
  vat_regime_confirmed = false` for costs and recurring costs with `vat_pct
  > 0` (a Belgian rate means a Belgian invoice; 0 %/null rows stay unknown
  and are listed under 'Nog te bepalen'); one payment row per paid cost
  without rows (`paid_on`, the cash total, the bank link, note 'Overgenomen
  uit Betaald op', actor 'migratie'). Parties, clearances and the setting
  stay null.
- **Out of scope, deliberately**: credit notes received on costs (a
  correction is an edit of the cost), line-level VAT, foreign VAT refund
  claims, the December advance (abolished), degressive depreciation, manual
  61/62 regularisations, a bank-side 'Aan kost koppelen…' action.

## Received Odoo documents and bank differences (2026-10-13)

- `OdooPurchaseImportService` reads a bank-specific received invoice/credit list, makes an
  immutable refreshed preview, downloads PDF/XML, and delegates all financial writes to
  `OdooCostImportStore`. See `docs/integrations/odoo-test-connector.md` for REST contracts.
- Source identity `(namespace, company, move ID)` and local cost are unique in
  `odoo_cost_import`; failed registration leaves only a retryable empty identity reservation.
  Keep that provenance if a local cost is deleted. No Odoo write occurs during this import.
- Original gross/net/VAT and provider draft/posted state are retained. Supplier credits are
  negative costs/payments. Mixed-tax invoices do not acquire a fictional single tax rate;
  classification/deductibility remain unconfirmed. Original source fields cannot be locally edited.
- `BankAmountMatch` is the shared signed EUR 0.02 tolerance, checked once on the whole
  allocation; never per split. `allocatedAmountEur`/`allocationDifferenceEur` are derived from
  real payment links and preserve both original bank and payment amounts.
- Migration `2026-10-13/odoo-cost-import-postgresql.sql` runs after the bank-account registry.

- Direct local bank-to-cost allocation accepts optional `confirmNewPayment` (default false).
  It refuses bank amounts above signed remaining cost plus EUR 0.02, and asks for reuse
  when an equal existing payment has no official imported bank link. Explicitly confirming
  a genuinely new equal instalment bypasses only that ambiguity, never amount/direction
  checks. Existing imported instalments and same-bank retries remain supported.
- New local expenses from a bank movement use the admin-only atomic
  `POST /api/bank-statements/{id}/cost-registration` (`requestId`, new `cost`). It holds
  the bank lock while validating and creating the cost, payment and link. Never return to
  client-side create-cost-then-link: uncertain responses and concurrent staff can otherwise
  leave duplicate costs. `bank_cost_registration` retains the original request hash and
  cost identity even after local deletion; identical retries return the same IDs, changed
  or competing requests return coded 409. Normal validation is 422 on this endpoint.
  The request ID is transport-only. Attachments are uploaded afterwards to the returned
  cost, preserving its identity on upload retry. See the integration document for details.

## Inkoopfacturen, betaalbewijs en betalingen in delen (2026-10-15)

Emre's round of 2026-10-14 (answers 2026-10-15): one commercial invoice per container that every
payment term points at, the bank transaction as the payment proof, a container paid in several
transfers, the inspection paid to the supplier in the goods' transfer, and per set / per stuk in
the nacalculatie.

- **Invoices** (`purchase_invoice`, `PurchaseInvoiceEntity` in its own file, `PurchaseInvoice`,
  `PurchaseInvoiceService`, `/api/purchase-orders/{id}/invoices` GET/POST/PUT/DELETE): payee
  stream, number, date, amount + currency + `amountEur` (given for a foreign invoice, else
  `LotCostCalculator.atContainerRate` for that payee; a result of zero is refused), optional
  `documentId` (the file is uploaded first through the documents endpoint, kind
  COMMERCIAL_INVOICE for the supplier, `INVOICE` "Factuur" otherwise), notes. Up to twenty per
  container. Writes lock the order and leave an activity record; no diary line. Invoices never
  become a company cost, never go to Odoo, never enter the VAT return or the accountant export.
  A second invoice of a payee is **extra on top** (adds up against the Afspraak); a correction is
  edited on the invoice itself; a supplier credit note stays a supplier credit. An order with
  invoices cannot be deleted (archive it). Deleting the file keeps the invoice data; deleting the
  invoice puts its payments back on automatic and keeps the file.
- **Which invoice a payment belongs to** (`InvoiceAssignment.resolve`, pure): the invoice the
  payment names (`purchase_payment.invoice_id`, explicit; null = automatic) when it is one of the
  order; else the payee's only invoice (two or more: none, the screen asks); else, for a SEPARATE
  payment that is `viaSupplier` (container flag on, and not on the bank yet or on a line that
  also carries a SUPPLIER payment of the order), the supplier's **primary** invoice. The primary
  is the payee's first registered invoice (lowest id): adding or redating a later invoice never
  moves it. Adding the second invoice of a payee **pins** the payments that resolved to the first
  one automatically (`Payments.setInvoice`), so an extra invoice never moves existing payments.
  A named invoice must be of the order and of the payee (a supplier invoice may carry an
  inspection only with the flag on); moving a payment to another payee silently clears a named
  invoice it may no longer carry. `PurchaseOrderView.invoices` carry `primary`,
  `coversInspection`, `paymentIds` and `paidEur`; `invoiceFiles` lists invoice files no invoice
  holds yet (COMMERCIAL_INVOICE, PROFORMA_INVOICE, INVOICE, OTHER labelled Factuur / Factuur
  inspectie / Factuur transport) for "Gegevens invullen"; each carries its `kind`. The list endpoint reads invoices, invoice documents and
  products once for all containers (Kosten & bank reads it).
- **Betaalbewijs**: the bank link is the proof (the ERP decides); `PAYMENT_PROOF` reads
  "Betaalbewijs". COMMERCIAL_INVOICE and INVOICE files are never proof. A credit offset is never
  a bank payment: `BankStatementService.link()` (manual movement) now refuses it like
  `linkExisting`, and `GET /api/purchase-payments` marks it `creditOffset`.
- **Payments in parts**: one `purchase_payment` row stays one transfer on its own bank day (what
  closings read). `group_id` = the id of the payment that was split, on every member (root
  included); null when never split. Invariant: a non-null `group_id` names an existing member
  that carries it. Deleting a member moves its files to the lowest remaining member (or to the
  container), a single remaining member loses its group, deleting the root re-roots the rest on
  the lowest remaining id (`Payments.setGroup`). Every in-place save of a payment carries the
  stored `groupId` and `invoiceId` (the 13-arg constructor is for new rows and read-only copies
  only; `LotCostCalculator.normalised` copies canonically). A PUT on a member of a group of two
  or more that changes payee, term, settles or invoice is refused ("Deze betaling is verdeeld
  over {n} transacties; pas de transacties samen aan") unless `applyToGroup: true`, which applies
  label, payee, settles, term and invoice to every member with that member's own day and money.
- **Split endpoints** (`PurchasePaymentSplitResource`, `/api/purchase-payment-splits/{paymentId}`;
  a prefix of their own so they never shadow the sourcing payment routes): `bank-lines` (C1, a
  payment over 1-10 free outgoing lines that make its amount under `BankAmountMatch`; one line =
  no split), `bank-part` (C2, a whole match, or a smaller line carved out with the rest left open
  in the group), `inspection` (D, the inspection split off a goods payment on the same line, line
  total unchanged, flag on, audit on the line). One transaction each; lock order is the bank
  service's (lines ascending, then the order, then the payment rows); links go through
  `linkExisting`, whose guards run per line unchanged. A part that goes onto a line takes the
  line's local day first (diary line replaced). Identical retries return the same group without
  writing; D needs `expectedAmountEur` for that. `linkExisting` (`/links`) itself is unchanged:
  a payment linked there keeps its date. Foreign parts are pro rata unless every amount is given;
  `normalised` values a foreign group together so a split never moves what is still owed, and
  `LotCostCalculator.calculate` does the same for the parts paid after the purchase (year-end lot cost).
- **Inspection via supplier** (`purchase_order.inspection_via_supplier`, null = no):
  server-owned like the archive. `PUT /api/purchase-orders/{id}/inspection-via-supplier`
  `{value}`; a SEPARATE payment write with `inspectionViaSupplier: true` (also a bank transfer
  part) sets it first. A form save keeps the stored value, a preview shows the stored value, a
  copy starts without. Turning it off is refused while a SEPARATE payment names a supplier
  invoice. The internal purchase PDF lists "Inspectie via leverancier · zelfde transactie".
- **Per set / per stuk** (`ReconciliationUnits`, applied in `PurchaseOrderService.reconciliation`
  with the products; the list endpoint and `LotCostService` pass the map they already hold):
  `Line` gains `salesUnit` (DISPLAY when sold as a display, else PIECE), `piecesPerUnit`,
  `unitKey`/`unitOne`/`unitOther`, `pieceQuantity`, `forecastExternalPieceEur`,
  `forecastPricingPieceEur`; `Totals` gains `setBasis` (PIECE, DISPLAY only when every line is a
  set of one size and piece name, else MIXED), `setPieces`, `setUnitOther`, piece quantities and
  the container average per piece. `unitCostQuantity`, `forecastExternalUnitEur` and the other
  existing quantities keep their **sales-unit** meaning: sales and partner settlements multiply
  them with sales quantities. The payments PDF prints "Aantal" and "per display" + "/stuk"; since
  round 2 it prints no average over different products (see below). Since 2026-10-15 every visible
  word is "display", never "set" (identifiers such as `setBasis` stay): the payments PDF puts
  "displays" under the count and "per display" under the figure, because "/display" does not fit
  its 8 % and 12.5 % columns on one line.
- Not removed on unlink (Codex's area, noted only): a `bank_cost_registration` receipt keeps a
  line answering 409 after an unlink.
- **Proforma (PI)** (2026-10-19, "Is dit de PI niet?"): kind `PROFORMA_INVOICE` "Proforma (PI)" is an
  invoice file of the SUPPLIER (room for ten like the other invoice kinds). It lists under
  `invoiceFiles` until an invoice holds it ("Gegevens invullen" makes it the supplier's invoice with
  data until the commercial invoice corrects it). Migration `2026-10-19/purchase-document-proforma-kind-postgresql.sql`
  widens the kind check like the INVOICE one and turns `OTHER` files labelled PI / Proforma / Pro forma /
  Pro-forma / Proforma invoice / Pro forma invoice (trimmed, any case) into the kind; registered last.
- Migrations `2026-10-15/purchase-invoice-postgresql.sql` (table, two payment columns, the order
  flag, indexes; no backfill) and `purchase-document-invoice-kind-postgresql.sql` (the kind check
  learns INVOICE) run after `bank-cost-registration-postgresql.sql`; dev H2 widens
  `purchase_document.kind` in `AllocationDevSchemaFix`. **Rollout: backend first**, wait until
  Railway serves it (a purchase order answer carries `invoices`), then the ERP; the ERP hides
  every new action against a server without this round. Payments that were "bewezen" only by an
  invoice file read "geen betaalbewijs" until their bank transaction is linked.

### Round 2 (Emre's follow-ups of 2026-10-15)

- **A linked payment follows its transaction** (`bank-part`, and `bank-lines` with one line): the
  payment takes the line's day and, for a whole match (± € 0,02), the line's exact amount (a euro
  payment's `amount` = `amountEur` = the line; a USD/CNY payment keeps its foreign `amount`, its
  `amountEur` becomes the line). A line more than € 0,02 above the payment is refused ("Transactie is
  € x hoger dan deze betaling: kies 'Bedrag aanpassen naar € y' …") unless the request carries
  `higher`: `ADJUST` (the payment takes the line's amount) or `INSPECTION` ("Rest als inspectie via
  leverancier": only for a goods payment to the supplier, not the CIF freight term, on a container
  with `inspectionCostEur` > 0; `PurchaseOrderService.addInspectionRest` books a SEPARATE payment of
  the rest on the same line and day, in the goods' currency at their own booked rate, and turns the
  container flag on). A smaller line is still carved out (C2). `withPaymentIds` names other open
  payments of the same container for the same transfer (the inspection paid with the goods): they
  keep their amounts and take the line's day; the addressed payment absorbs every difference, never
  the inspection, so the line total equals the parts. `expectedAmountEur` (optional) refuses a
  payment that changed since the screen read it. Addressed to the root of a payment in parts that is
  on another line, `bank-part` links the group's one open transfer. One transaction:
  `PurchaseOrderService.followBankLine` rewrites the diary line in place and records "Betaling aan
  een banktransactie gekoppeld" with the date / amount / euro before and after; then `linkExisting`
  links. The answer's `notice` says what changed ("Bedrag en datum gevolgd van de transactie:
  € 10.300,00 op 05/01/2026 (was € 10.250,00 op 03/01/2026).", "Deel van deze betaling: … blijft
  open als tweede transactie.", "… Inspectie via leverancier € 300,00 toegevoegd op dezelfde
  transactie."; null on an identical retry). `/links` (several payments, Kosten & bank) is
  unchanged: two-cent rule, no following. The inspection split (D) is refused on a line that already
  carries an inspection payment of the container ("Deze transactie draagt al Inspectie € x (via
  leverancier); een tweede afsplitsing kan niet. Maak de koppeling los om de inspectie te wijzigen.").
- **USD/CNY payments on `bank-part` decide in their own currency** (review of round 2): the line holds
  euro only and the payment's euro is a forecast at the order's rate, so a euro gap is the exchange
  rate. Without `amount` (or with the payment's whole amount, or `higher: ADJUST`) the line is the
  whole payment: `amountEur` := the line, `amount` kept, no "hoger" question either way. A smaller
  `amount` in the payment's currency carves that part (at the payment's booked rate, then the part
  takes the line's euro; the rest stays open at the booked rate; notice "Deel van deze betaling:
  US$ 6.000,00 (€ 5.090,00) van US$ 12.000,00; de rest (US$ 6.000,00) blijft open als tweede
  transactie."). A larger `amount` and `higher: INSPECTION` are refused (the inspection is split off
  after the link). The ERP's link plan and Kosten & bank decide the same way.
- **"Rest als inspectie" never books a second inspection**: refused (and left out of the 409 text)
  when `withPaymentIds` names an inspection payment ("Op deze transactie staat al Inspectie € x (via
  leverancier); een tweede inspectie kan niet. Kies 'Bedrag aanpassen naar € y'.") or when the
  container's inspection paid through the supplier (`InvoiceAssignment.viaSupplier`) already reaches
  `inspectionCostEur` ("Inspectie via leverancier staat al geboekt: € x van € y inspectiekost; …").
- **"Transactie toevoegen"**: `bank-part` addressed to a payment whose transfers are all on the bank
  answers 409 "Alle transacties van deze betaling zijn gekoppeld: kies 'Bedrag aanpassen naar
  € 12.500,00' om deze transactie (€ 500,00) toe te voegen."; with `higher: ADJUST`
  `PurchaseOrderService.addTransfer` adds a transfer of the line's euro (USD/CNY: `amount` as named or
  at the group's own rate) on the line's day to the payment's group (one row becomes a group), with
  its payee, term, settlement, description and invoice, and links it in the same transaction
  (activity "Transactie toegevoegd aan de betaling", notice "Transactie van € 500,00 op 24/09/2026
  toegevoegd; de betaling is nu € 12.500,00 (was € 12.000,00)."). The linked transfers never change,
  so the bank lock on a linked payment's amount stays.
- **One invoice for every part-payment**: `POST /api/purchase-orders/{id}/invoices/{invoiceId}/assign-all`
  names the invoice on every payment of its payee (each transfer of a payment in parts and payments
  already on the bank included; column-only, no diary line, one activity record) and answers every
  invoice of the container. A supplier invoice also takes the inspection paid through the supplier
  while no inspection invoice exists; `coversInspection` is true on the supplier invoice that
  inspection resolves to (the primary while none is named). **Invoice file uploads go to the invoice**, never to a payment: a
  multipart `POST /api/purchase-orders/{id}/invoices` (the JSON fields as form fields plus `file` and
  `assignAll`) checks the data before keeping the file, stores it at container level
  (COMMERCIAL_INVOICE for the supplier, INVOICE otherwise) and creates the invoice holding it;
  multipart `POST …/invoices/{invoiceId}/document` (`file`, `assignAll`) gives a file to an invoice
  that has none ("Bij factuur {number} hoort al een bestand (…); verwijder dat eerst" otherwise).
  The list endpoint now also names an invoice's file of another kind.
- **No average over different products**: `Totals.productCount` and `Totals.containerAverage` (true
  only for a container of one product; since the round-2 screen check also false for variants of one
  family, because Emre said an average over several products "klopt niet"). Without it the server
  notes say the cost per stuk or per display is per product (the round-1 note on a container average per
  piece is gone) and the payments PDF reads "verdeeld over n producten (…)", its total row "Totaal"
  with "per product" where an average stood (the PDF also checks the lines, so a report that skipped
  `ReconciliationUnits.apply` never prints one; "/stuk (gem.)" is gone). The piece figures stay in
  the data. The internal calculation PDF (`purchase.html`) uses the same rule
  (`ReconciliationUnits.containerAverage` over the costing lines): one product keeps "Gemiddeld per
  stuk" (a display: "Per display" and "Per stuk · 12 rozen per display", the table's display lines and
  total add "per display" and the cost per stuk); several products show "Per stuk · n producten" with
  the range of their own landed figures ("4,138 – 5,753 EUR", one figure when equal; all displays:
  "Per display" and "Per stuk" ranges; displays next to pieces: per stuk) via
  `ReconciliationUnits.range`, the total row "per
  product". Different duty rates read "5 % – 12 % per product" instead of "x % gemiddeld".
- **Enrosed kost per product in the payments PDF**: columns "Extern / stuk", "Enrosed kost" (the
  line's `internalMarkupEur`, the same split as the screen) and "Incl. Enrosed kost / stuk" (per
  display and per stuk for displays); the total row keeps the total, the box below reads "Enrosed kost" and
  "Totaal incl. Enrosed kost".

## Kasprognose en bank (2026-10-16)

Emre's round of 2026-10-15: Kosten & bank opens on the bank, Analyses gets a Kasprognose (cash forecast) with
choices per container, and a bank transaction can be booked without an invoice. The forecast itself is computed in
the ERP (`cash-forecast.ts`); the backend stores the choices and the facts it needs.

- **Choices per container** (`purchase_order_forecast`, `be.enrosed.finance.forecast`, `/api/purchase-order-forecasts`
  GET, PUT `/{orderId}`, DELETE `/{orderId}`): price mode (MARKUP_PCT, EUR_PER_UNIT, LIST, HISTORIC), markup %, markup
  per unit, cost basis (EXTERNAL, PRICING), sell weeks, payment days, seasonal, included ("Meetellen"), the expected
  ship date of an ordered container and the planned order day of a concept. They live in their own table, not on
  `purchase_order`: the order PUT replaces the whole record and an older ERP build would null a column it does not
  know. Every PUT is the whole set; all null removes the row (the answer then has every field null and no
  `updatedAt`). Refusals in order: unknown or trashed order (404, looked up through `PurchaseOrderService.get`), price
  mode, markup 0–1000 with two decimals, per unit 0–100000 with four, cost basis, weeks 1–104, days 0–365, then the
  order-state rules (partner container: no price or pace; ship date not on a departed order nor before the order date;
  planned order day only on a concept and not before today). Those last rules check only a value that differs from the
  stored row, so a control may repeat a stored value after the order moved on. PUT and DELETE first lock the
  `purchase_order` row (the payment paths' lock): before the first choice there is no forecast row to lock, and two
  PUTs sent together would both insert it. Deleting an order is a soft delete: the
  row stays, GET skips it (`deleted_at is null` written out), PUT answers 404, a restore brings the choices back. No
  diary line, no activity record. The resource has its own prefix: a second class under `/api/purchase-orders` would
  shadow the sourcing routes.
- **Buffer** (`company_profile.cash_buffer_eur`, `/api/cash-forecast/settings` GET/PUT): `{cashBufferEur,
  cashBufferStored}`, EUR 10.000,00 and false while nothing is stored, 0–10.000.000 in cents, null clears. The profile
  PUT never touches the column; a missing profile row is first created by the profile's own save (so it keeps the
  seeded identity). The ERP reads a 404 here as "server without this round" and hides every phase-2 control.
- **Accounts that count** (`bank_account.cash_excluded`, null counts): `BankAccountService.Account.countsInCash`; PUT
  `/api/bank-accounts/{key}/cash {counts}` creates the row like archiving, refuses an alias, audits "Telt (niet) mee
  in de kasprognose". Only the forecast start reads it; the account stays on Bank.
- **Cost due date** (`company_cost.due_date`, `CompanyCost.dueDate`, 25th component, left out of the JSON while null
  so the bank registration hash of an old retry stays the same): POST stores it, the cost PUT keeps the stored value
  (server-owned), PUT `/api/costs/{id}/due-date {dueDate}` sets or clears it; before the cost date is refused (POST and
  that endpoint). There is no 24-argument constructor: every copy names the due date and must carry the stored one
  (update, validated, paymentState for every payment write and markPaid, the imported update, the entity); new costs
  of the recurring job, the Odoo import and the bank registration start without one.
- **"Boeken als"** (`BankLineClassificationService`, POST/DELETE `/api/bank-statements/{id}/classification`, six
  nullable plain-text columns on `bank_statement_line`): INTERNAL_TRANSFER (optionally with the other leg: another own
  account by canonical key, the exact opposite amount, within five days, free; the two legs point at each other and
  are booked and undone together), VAT (`vatPeriod` 'YYYY-Qn'), LOAN, CAPITAL, PRIVATE, OTHER (note required). Locks in
  ascending line id; an identical retry answers the lines. `describe` reports a classified line as allocated with its
  Dutch label, the line amount and no difference, so it leaves Te koppelen, an Odoo re-import that changes it is a
  CONFLICT and the split operations refuse it. Every link path calls `BankStatementService.requireUnclassified` first,
  before any retry shortcut ("Deze banktransactie is geboekt als {label}; maak dat eerst ongedaan"): allocate, /links,
  /cost-payment, /purchase-payment, /cost-registration (422 there), the Odoo invoice link (and its preview) and
  deleting a manual line. Unlink and unallocate never touch a classification.
- **`GET /api/bank-statements?from=YYYY-MM-DD`**: lines booked from that day (00:00 Brussels) plus every line still to
  link, whatever its age; filtered in Java after `describe` (the allocation is transient). Without `from` unchanged.
- **Follow the bank** on `/links` (`followBank`): exactly one cost payment (named, or a cost's only one), no purchase
  payments; before the two-cent check the payment takes the line's day, amount and account through
  `CompanyCostService.updatePayment` (refused above the cash total + EUR 0,02), then today's link. On `/allocation`
  (`followBank` with `existingPaymentId`) the receipt takes the line's amount, moment, zone and account through
  `IncomingPaymentService.update` before the match. Purchase payments follow through round 2's bank-part.
- **Keep the payment, book the difference** (`/links` with `keepDifferenceAs` BANKKOSTEN or KOERSVERSCHIL, purchase
  payments only, the bank paid more than EUR 0,02 above them): a confirmed company cost is created **unpaid** (with a
  paidOn `create` would add its own payment), EXEMPT "Bankkosten · {counterparty}" (VERZEKERING) or OUT_OF_SCOPE
  "Koersverschil · {orders}" (ANDERE), then one payment on the line's day and account noted "Verschil bij
  bankkoppeling", then the purchase payments and that payment are linked together. A retry that finds exactly those
  purchase payments plus one such payment of the difference answers the line before creating anything.
- **"Aangepast aan de bank (was ...)"** (`purchase_payment_bank_adjustment`, entity in sourcing persistence, port
  `SourcingRepositories.PaymentBankAdjustments`, optional `Instance` like the payments): every whole follow in
  `PurchasePaymentSplitService` (bank-part whole, ADJUST and INSPECTION, bank-lines with one line, and the payments
  named with it that take the line's day) calls `PurchaseOrderService.recordBankAdjustment(before, lineId)` after its
  own update; a row is written only when the euro amount or the day changed and only when none exists (the first
  adjustment wins). A carved part is a new transfer and writes nothing. The row goes only by the payment sheet's hand
  PUT (`updatePaymentByHand`, which `SourcingResource` now calls; a changed amount or date) and by deleting the
  payment, never inside `updatePayment` itself (a follow path may reuse it). `GET /api/purchase-payments` carries
  `bankAdjustedFromEur` and `bankAdjustedFromOn` (one read for the list).
- **One transfer for several containers** (owner addition): each part of `POST /api/bank-statements/{id}/purchase-payment`
  may name its own `purchaseOrderId` (the request's is the default and may be left out). The line, then every order in
  ascending id, are locked; the parts' total must make the line (two-cent rule); each part is a payment on its order;
  all are linked to the one line, which is the proof for every part. Identical retries answer the same ids.
- **Verdelen over containers** (Emre, 2026-10-16: one transfer paid the instalments of three containers, some already
  noted): `BankPurchaseShareService` + `POST /api/bank-statements/{id}/purchase-share` `{parts:[{paymentId, amountEur,
  expectedAmountEur?} | {purchaseOrderId, amountEur, payee, instalmentDue, label, settles, invoiceId?}]}`, 1-10 parts,
  one `@Transactional`. An open payment part is linked and follows the line's day (`followBankLine` +
  `recordBankAdjustment`: same euro LINKED, more GREW, less CARVED through `splitPayment` with the rest left open in its
  group); USD/CNY is whole (its euro becomes the part, its own amount stays); a container part gets a new EUR payment
  (CREATED, ids ascend in request order). Locks: line, orders ascending, payments ascending; parts make the line within
  EUR 0,02; every check (credit offset, already on the bank, changed `expectedAmountEur`) runs before any write; one
  `bank.linkExisting` with every row, so its guards run unchanged. An identical retry answers the same rows without
  writing; any other request on a line with purchase payments is refused ("Deze banktransactie is al aan een andere
  betaling gekoppeld"). Each order gets one activity line
  (`PurchaseOrderService.recordBankShare`), the line one bank audit ("Verdeeld over 3 containers: …"). No schema change.
  The ERP sends it only when a part is an existing payment; `/purchase-payment` stays as it was.
- **Migrations** `2026-10-16/cash-forecast-postgresql.sql` (the forecast table, the buffer, the due date, the account
  flag) and `bank-classification-postgresql.sql` (the classification columns with an index on the other leg, the
  adjustment table) run after `purchase-document-invoice-kind-postgresql.sql`, in that order. Additive: no update,
  insert, check or foreign key. **Rollout: backend first**; the ERP hides every phase-2 control until
  `/api/cash-forecast/settings` answers.
