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
run `mvn quarkus:dev`). There are no automated tests: Emre had them all
removed on 2026-10-28; do not write or run tests, check changes by building
and trying them live. Hexagonal-lite per feature:

```
be.enrosed.catalog | sales | sourcing | shared
   domain/        records, no framework
   application/   services, ports (in/out interfaces)
   adapter/in     REST resources (Basic auth: named staff principals, RolesAllowed)
   adapter/out    Panache entities+mappers, PDF renderers, mail, market
```

**Security rule (since the partner portal, 2026-11-10):** a second kind of login exists, the
partner (role `partner`, never `admin`). Every staff resource stays `@RolesAllowed("admin")`
(`AdminIdentityProvider.ADMIN_ROLE`); never put `@Authenticated`, `@RolesAllowed("**")` or the
`partner` role on a staff resource, and never list a partner username in `ENROSED_ADMIN_ACCOUNTS`.
Only `/api/auth/me`, `POST /api/auth/session` and `/api/partner-portal/*` admit a partner. See
"Partnerportaal (Verhoeven), 2026-11-10" at the end.

Dev DB: H2 file (`./data`, schema update). Prod: Postgres via PG* env vars
(Railway). Domain types are records; construction goes through services;
`withStatus`-style copy helpers keep transitions in one place.

## Domain scenarios (the business rules, as discussed)

### Sales / quotes
- **Shared invoice numbering**: set company `invoiceNumberPrefix=F`,
  `partnerInvoiceNumberPattern=F-{jaar}-{nr}` and clear
  `partnerInvoiceNextNumber` to put ordinary, advance and partner invoices in
  one `F-<year>-0001` series. Use exactly `{nr}` here, without a digit suffix,
  so both paths also continue earlier letter prefixes. Existing document
  numbers and their IDs stay unchanged; quotes and credit notes keep their
  own series. `Orders.lockNumbers()` holds the dedicated PostgreSQL transaction
  advisory lock (H2 transaction-lifetime lock in tests) while selecting the
  next number or checking a manually entered number, until save and commit.
  Deleted and cancelled rows still reserve their numbers. A rolled-back
  creation does not consume a number. No schema migration is required.
- **Container sale margins**: `createFromPurchaseOrder` keeps the configured
  container price (including `extraRevenueEur`) as the sale price, but removes
  each landed-cost line's allocated `extraRevenueEur` from `unitCostEur`.
  The remaining external cost is frozen on the sale and copied to its invoice;
  editing without a replacement cost preserves it. On an ordered container
  this remains a cost estimate, not a reconciliation of actual payments.
- **Final invoice PDFs** label their financial total as total including VAT;
  only the payment panel states the remaining claim, so a paid final invoice
  never labels its original total as money still due.
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
  freight counts THEM instead of the calculated stacking (strict or mixed
  per document, see "Automatische pallettelling" below); unassigned
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
- What is coming per product (2026-11-10): `GET /purchase-orders/
  expected-stock` (`PurchaseOrderService.expectedStock(boolean)`). The
  five old fields (`productId`, `quantity`, `expectedArrival`,
  `orderNumbers`, `orderIds`) keep their meaning: BESTELD + ONDERWEG
  only. Additive: `orders` (one row per purchase order: `orderId`,
  `orderNumber`, `name` = Herkenbare naam else number, `status`,
  `quantity`, `expectedArrival`, `shippedOn`, `receivedOn`, `partner`)
  and `arrivedQuantity`. Only with `?arrived=true` (the ERP's product
  screens and sales picker) a received container whose stock is not
  booked (ONTVANGEN, `stockBooked` false, **not archived**, received at
  most `ARRIVED_DAYS` = 30 days ago: an older one had its pieces counted
  into stock some other way and only keeps its "nog bij te boeken" on
  the purchase order) counts: its
  usable pieces go into `arrivedQuantity` and an ONTVANGEN row, never
  into `quantity`; a product with nothing else coming then gets an entry
  with quantity 0. Without the parameter the entries are exactly the old
  ones (Analyses and an older frontend). A line without a product or
  with nothing to come is skipped and an order without a number reads
  "Inkoop #id": old data never fails the whole answer (it used to 500).
  Concept and trashed orders never count; archived Besteld/Vertrokken
  orders still do. Partner containers count: `bookStock` books every
  line into the same stock levels (the batch is only marked
  `Owner.PARTNER`). Nothing is stored; the state words (Besteld,
  Onderweg, Binnenkort, Aangekomen) are the frontend's
  (`shared/product-incoming.ts`), because "Binnenkort" depends on today.
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
  that run into it are laid out again in the compact design; a compact sheet
  that still runs into it gets the tight design (`family-page--tight`: a
  smaller hero and colour strip, every row kept), and a tight sheet that
  still does not fit the bare one (`family-page--bare`: no colour photos,
  gallery or long description), so the variant table with its prices always
  stays whole (2026-11-06: before it a long family could lose its whole
  table off the page). The same passes measure the
  overview pages: they start at 10 rows per page (it was 12; pallet, 20ft and
  40ft take a line more per family) and a layout whose range table runs into
  its footnote is laid out again below its fullest page, down to 6 rows.
  At most five layouts (only the last writes the PDF); whatever still does not
  fit is logged as WARN ("Catalogusfiche … is ook compact … mm te lang",
  "Overzichtspagina N loopt … mm in de voetnoot"), the room left at DEBUG.
  `renderHtml` shows the first layout only. Before any layout,
  `ensureFamilyPageCapacity` refuses a family whose text budget passes 3 400
  units (409 "Cataloguspagina voor … bevat te veel tekst"; the budget per
  sheet is logged at DEBUG). Pallet, 20ft and 40ft count per variant only
  when the variant table prints them per variant (`capacitiesDiffer`);
  shared, they are specification rows and count as such, never twice. In
  the overview's narrow price cell a display total puts its amount under
  its words ("Display (12 st.):" / "€ 49,00 - € 54,00", split at the last
  ": "), and every price there wraps at its spaces (a range after its
  dash; an amount never breaks inside), so a long one stays inside the cell
  instead of running over the logistics column.
- **Pallet, 20ft and 40ft always together** (Emre, 2026-11-06), in that order,
  in every layout: the compact facts (Europallet "864 st. (72 omdozen)",
  20ft, 40ft HC), the brochure range table (one line of three figures that
  wraps between figures), the family specification list (the pallet with its
  omdozen in brackets) when every variant shares all three, else one line
  under each variant row of the variant table with all three (never split
  between the two). An unknown figure prints "-", 40ft included, which used
  to disappear. Per pallet = whole omdozen from
  `PalletCalculator.fit` (the Palletberekening stacking) on
  `SalesSettings.pallet()` × pieces per carton, worded like the container
  figures (displays, named units). Catalog reaches it through the out-port
  `CataloguePallet`, implemented in sales by `CataloguePalletFit` (catalog
  never imports sales). One basis line per document ("Per pallet: europallet
  120 × 80 cm, max. 260 cm hoog incl. pallet, max. 700 kg.", 220 from
  2026-11-07 to 2026-11-10, key
  `catalog.common.palletbasis`) closes the overview footnote and the compact
  footer. A carton without a weight is counted by height only, and there is
  no hand count per product for the pallet.
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
- **Per piece on one line (2026-11-10, "Verkoopofferte: aantallen per
  stuk").** `SalesOrderLine.perPiece` (`sales_order_line.perpiece`, nullable
  Boolean; TRUE or null, never FALSE; left out of the JSON while null) says
  staff chose that this line is not rounded to its product's step. Such a
  line behaves as a line of a UNIT product: the quantity stays as typed, the
  stamped `unitsPerOrderStep` is 1, the piece price is the same, and a part
  carton still counts as one full carton for cartons, m³, kg, pallets and
  freight. `SalesPricingCalculator.unitsPerOrderStep` returns 1 for it
  whatever is stamped, and `withOrderSteps` rounds with 1.
  Request rule (`SalesOrderService.withPerPieceChoices`, PUT and preview):
  on a document that rounds, an existing line id (same product) keeps the
  stored choice when the field is absent or null, true or false sets it; a
  new line without the field is not per piece. On a document that keeps
  exact pieces the request's value is not used (stored stays, new line
  none). A document whose lines are fixed by an advance refuses a change of
  the choice (`sameCargoLines`, `sameQuotedLines`). No other endpoint takes
  it; the website never sets it and keeps rounding by the product's step.
  `PricedOrder.Line` adds `perPiece` (the line's choice) and
  `perPieceChoice` (TRUE where a staff screen may offer the switch: the
  document rounds and the product's step of today is above 1), both left
  out unless true; `orderStep` stays the product's setting. Customer-facing
  readers ask `PricedOrder.Line.customerOrderStep()` (UNIT for such a line):
  the quote link line, account documents and the web order snapshot; the
  quote link's product list answers UNIT / 1 for a product whose line on
  that quote is per piece, so a proposal is not snapped
  (`QuoteService.roundToOrderSteps` does not round it either), and the
  team mail words it in pieces. Carried by: invoice from quote, Nieuwe
  kopie, delivery weeks, both parts of a split, proposal approval, and a
  credit note line (from the credited invoice line, also for a line added
  to a concept credit note). Logboek: "Aantal per stuk · Product N" = ja.
  Migration note: `docs/migrations/2026-11-10/sales-line-per-piece-postgresql.sql`.

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
  prices in the quote request even while the website price switch is off
  (their own agreed prices where they have them, else the public list), and
  the request lands on the existing customer. Package
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
  restore), `session/logout`, `activation/inspect`, `activation`, the
  priced quote endpoints `quotes/configuration`, `quotes/preview`,
  `quotes/requests` (`CustomerQuoteResource`), and `prices` (the customer's
  own agreed prices, `CustomerPriceListResource`). Form token purpose
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
  reads a credential. Price basis (Emre, 2026-11-06, replacing "the public
  list for everyone, no customer prices"): the customer's own ACTIVE agreed
  price (klantprijs, `CustomerPriceService.activeFor`) wherever they have
  one, else the public list; a partner customer gets no landed cost. It
  applies on the server, on the logged-in paths only:
  `configurationForCustomer` (`products[].priceSource: "CUSTOMER"`), every
  logged-in estimate (`prepare(..., pricesOf)`: a kept price first, then the
  agreed price as the line's manual price, else the list;
  `lines[].priceSource: "CUSTOMER"`), and the stored lines of a logged-in
  request or order (pinned with `priceSource` CUSTOMER: the website path is
  the one place that pins website lines, the staff pinning skips them). Both
  `priceSource` fields are `NON_NULL` and only the account route sets them,
  so every anonymous configuration, estimate and stored idempotency answer
  reads byte for byte as before. The anonymous routes never look a customer
  price up; anonymous submits always create a new customer. Customer prices
  never travel on anything public (families, layout, photos, revision
  digest, rebuild outbox): a saved customer price is live on the website for
  that customer at once, with no publish step and no rebuild.
- **`GET /api/v1/public/account/prices`** (`CustomerPriceListResource`,
  for the product pages): `{currency: EUR, priceBasis: NET_EXCL_VAT,
  prices: [{productId, unitPriceNet, salesUnit, piecesPerDisplay}]}`, only
  `websiteOrderableProducts()` with an active agreed price, never the list
  price; `piecesPerDisplay` as in the configuration. Guard first (401
  `SESSION_INVALID`), `Cache-Control: no-store, private`, its own budget
  `ACCOUNT_PRICE_READ` 600 an hour per account through `checkKey` (no IP
  bucket, no e-mail bucket), not tied to the ordering switch.
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
  (`Customer.withDeliveryAddress`), VAT from the record, each line at its
  kept price, else the customer's active agreed price, else the list. The
  stored lines pin the estimate's unit price (`orderDocument`), with
  `priceSource` CUSTOMER where it is the agreed price and the kept line's
  own source for a kept price. The sales core prices the stored document the
  same way through `WebOrderDeliveries.pricingCustomer`, so the estimate
  equals the stored order, the snapshot and `ordered_terms`, its invoice, a
  split part and a "Nieuwe kopie". The customer record is never written by a
  customer path.
- **Snapshot**: `sales_web_order.order_snapshot` freezes the ERP's own
  pricing of the saved document at placement and at every customer change
  (`WebOrderSnapshot`); `ordered_terms` is its fingerprint
  (`WebOrderTerms`), null when freight, a price or carton content was
  still open. The customer keeps seeing the snapshot until the order was
  sent, so unsent staff edits never show under "my orders".
- **Customer change**: until staff take the order the customer replaces
  it whole (`SalesOrderService.updateByCustomer`). A product that stays
  keeps its line id, its unit price (and its source) and its cost; a new
  product gets today's price for this customer (agreed price, else the
  list; Emre's Q3); order date and validity stay. `revision` counts the
  customer's versions (cap 20). The Dutch change summary
  (`customer_change_summary`) goes to the team mail, the push, the history
  and the ERP banner and names a kept price that is no longer what a new
  line would cost ("prijs X behouden: € a, klantprijs nu € b", or
  "prijslijst nu € b"; `OrderFacts.listPrices` / `customerPriced`).
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
- `java -jar target/quarkus-app/quarkus-run.jar` is built for PostgreSQL:
  `quarkus.datasource.db-kind` is fixed at build time by the prod profile, so
  the packaged app refuses an H2 URL. To try a packaged build on the H2 file,
  build with `mvn package -Dquarkus.profile=dev` and run it with
  `-Dquarkus.profile=dev`; to try the real jar, give it a scratch PostgreSQL
  (PG* variables, plus `SMTP_HOST` and `PUBLIC_FORM_HMAC_SECRET`, which the
  prod profile requires).

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
  Keep that provenance if a local cost is deleted: automation (Linc sync, TICA) never imports that source again.
  Only the user's own registration releases the binding of a deleted cost and books a new cost on it, in one
  transaction (FIX2, 2026-10-26). No Odoo write occurs during this import.
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
  Unlinking the line (`DELETE …/links`) keeps the receipt: its cost stays paid, so another
  request answers 409 `BANK_COST_REGISTRATION_EXISTS` naming that cost ("koppel die opnieuw
  of verwijder die eerst") and a pending retry 409 `…_CHANGED`. Once that cost is deleted, a
  new request registers the line again on the same receipt (its own retries return its IDs;
  the old request's retry still never recreates the deleted cost). Deleting a manual line
  removes its receipt; the cost stays.
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
- **Proforma (PI)** (2026-10-19, "Is dit de PI niet?"): kind `PROFORMA_INVOICE` "Proforma (PI)" is an
  invoice file of the SUPPLIER (room for ten like the other invoice kinds). It lists under
  `invoiceFiles` until an invoice holds it ("Gegevens invullen" makes it the supplier's invoice with
  data until the commercial invoice corrects it). Migration `2026-10-19/purchase-document-proforma-kind-postgresql.sql`
  widens the kind check like the INVOICE one and turns `OTHER` files labelled PI / Proforma / Pro forma /
  Pro-forma / Proforma invoice / Pro forma invoice (trimmed, any case) into the kind; registered last.
  **Deploy window, revert or image rollback**: code from before this change has no
  `PROFORMA_INVOICE`, and Hibernate then fails on every such row, so that container's view and its
  documents answer 500. The backfill runs in the pre-deploy step, so this holds while the old image
  still serves during the deploy too. Before or right after starting an older image, run
  `UPDATE purchase_document SET kind = 'OTHER' WHERE kind = 'PROFORMA_INVOICE';`. The label stays, so
  a redeploy turns the labelled files back into the kind; a PI uploaded under another label stays
  `OTHER` and is set again by hand. An invoice that holds such a file keeps it.
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
- **Transactiekosten on `bank-part`** (2026-10-19, the bank's fee on a Chinese transfer): `higher: FEE`
  ("Rest als transactiekosten", EUR payments) keeps the payment's amount and books the rest as a new
  payment on the same container and line: payee OTHER (Bijkomende kosten: no Afspraak, in the
  nacalculatie, out of the stock value, never a company cost), label "Transactiekosten", EUR,
  `settles` false, no term, the line's day. `feeEur` (nullable, last `PartRequest` component; the 2- and
  5-arg constructors stay) names the fee in the line: it is booked the same way and the addressed
  payment follows line − others − fee by the usual rules (EUR exact or `higher`, smaller carved,
  USD/CNY takes that euro, "Transactie toevoegen" grows by it); with FEE too the rest joins the same
  fee payment. Refused: fee ≤ 0, fee ≥ the line, more than two decimals, FEE on a whole USD/CNY link
  ("geef de transactiekosten op in euro"). Notice "… Transactiekosten € x bij {PO-nummer} op dezelfde
  transactie."; an identical retry returns before any write (the payment is on the line already).
  Unlink keeps the fee payment and only frees it, like the inspection rest; a later fee on `bank-part`
  takes the container's open "Transactiekosten" payment (EUR, no group; one of the same amount first,
  another amount it follows) instead of booking a second, so Bijkomende kosten never carry one bank fee
  twice. `/purchase-payment` (a term
  + an OTHER "Transactiekosten" part) and `/purchase-share` (an open payment + a new OTHER part of the
  same order) already took this; tests pin both.
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

## Transactie losmaken zonder halve koppeling (2026-11-04)

Emre: "als ik 1 vd 2 transactie verwijder popup doen of ik zeker ben en alles dekoppelen". DELETE
`/api/bank-statements/{id}/links` only cuts the line: a transfer of a payment in parts then stayed on its payment and counted
as paid without a bank line (reproduced: the term read paid and finalized), the Transactiekosten booked on the line stayed as
Bijkomende kosten, and the Bankkosten/Koersverschil cost of `keepDifferenceAs` stayed paid (a new link books it twice).

- `BankLineUnlinkService` (`GET /api/bank-statements/{id}/unlink-plan?keepParts=`, `POST …/unlink {keepParts, expected}`): one
  plan, then the same rules done in one transaction. Per purchase payment on the line: Transactiekosten (OTHER, label
  `FEE_LABEL`, EUR, no group) DELETED; a transfer of a payment in parts MERGED into the payment's open transfer when it has one
  (`PurchaseOrderService.mergeTransfer`: the lower id stays with both amounts on the open one's day, the other row goes, its
  files to the lowest remaining transfer, both bank adjustments forgotten), else REMOVED from its payment
  (`deletePayment` after the unlink), or with `keepParts` STAYS open on it; every other payment STAYS without the line. Per
  cost payment: the difference cost (its only payment noted `DIFFERENCE_NOTE`, description Bankkosten[ · …] or Koersverschil
  · …) DELETED through `CompanyCostService.delete`, others STAY (the cost stays paid). A sales receipt and a classified line
  are refused as before. `expected` is a fingerprint of what the line carries (and the groups of its transfers): a changed
  line answers "Wat aan deze transactie hangt, is intussen gewijzigd; bekijk het opnieuw". The line gets one audit line.
- DELETE `…/links` is unchanged (the ERP's 'Splitsing aanpassen' still uses it: it links the same payments again).
- No schema change.

## Automatische pallettelling: strikt of gemengd (2026-11-04)

Emre: "Ja, gemengd tellen". Without hand-built pallets the freight (COUNTRY_PALLET, CARRIER), the PDF, the portal and
the website quote count pallets automatically; that count follows a rule stored per document, so a document that left
concept keeps the count and freight it was sent with.

- `PalletCountRule`: STRICT = every product on pallets of its own (every document's count until now); MIXED = products
  share pallets in whole layers of their own (`PalletCalculator.mixedPallets`; `planPallets` in the frontend's
  `pallet-calculator-model.ts` must take the same steps): (1) the placeable lines (cartons, cartonsPerPallet and a
  carton height above 0) tallest carton first, ties in line order; (2) per line a running rest: a layer of
  min(perLayer, rest) goes on the first pallet where one more carton height stays under max − base and the 700 kg
  still take a carton, n = min(layer, what the weight takes), else on a new pallet; that layer is n cartons as high as
  a full one, and the cartons a weight split leaves join the next layer instead of making a short one, so one product
  alone gets exactly its cartonsPerPallet per pallet; (3) a plan longer than the separate count gives way to the
  separate layout (`separatePallets`), so `palletsAutomatic` never exceeds `palletsStrict`. Cases (worked out at the
  260 cm standard; at the 220 cm of 7-10 November 2026 the 60×40 mix below takes 2 pallets, the others are unchanged): 238 × 40×40×50 cm
  at 30.7 kg on Euro 260 cm 11 (= strict), 33 × 58.5×20×50 at 100 kg on a half pallet 5, 23 × 20×40×200 at 100 kg on
  a block pallet at 300 cm 4, 60×40×50 8 kg ×16 + 60×40×40 6 kg ×4 one pallet, the four products 1 at 204.4 cm and
  159 kg (strict 4), four 100 cm singles 2. Checked against those steps written apart on 60,604 orders (no difference,
  none above strict). `palletsOptimised` keeps its old fraction estimate.
- `sales_order.palletcountrule` varchar(16), no CHECK; the entity column is not updatable and the `SalesOrder` record
  does not carry it, so no save, rebuild or with-copy can change it; null or an unknown value reads STRICT.
- Older rows get their rule in two steps. The migration `docs/migrations/2026-11-04/sales-order-pallet-count-rule-postgresql.sql`
  writes, only in the run that creates the column, STRICT on every row except an untouched concept quote (docType
  null/OFFERTE, status CONCEPT, `deleted_at` null, `sentat` null, no `sales_web_order` row, no `sales_advance_billing`
  row with `quote_id` = it, no `sales_order` with `sourcequoteid` = it, no `sales_split_group` with it as
  `root_order_id` or `later_order_id`, no `partner_advance_quote_snapshot` row); those stay null. Then
  `PalletCountRuleBackfill` (a scheduled job, `pallet-count-rule`, first run 10 minutes after the start and then every
  10 minutes: `enrosed.pallet-rule.decide-after` / `decide-every`; the same test word for word in
  `UNTOUCHED_CONCEPT_QUOTE`, plain SQL for PostgreSQL and the dev H2) gives every null row MIXED when it is such a
  quote and STRICT otherwise. Not at start-up: Railway keeps the old deployment serving until the new one passes its
  health check (healthcheckTimeout 300 s), and a deployment that fails that check never runs the job, so a quote the
  old release sends, advances or invoices in that window is decided by what it is once only the new release serves:
  a quote sent with the strict count keeps it (proved with a base-vs-head run on PostgreSQL, overlap and failed deploy
  included). Until decided a null row reads STRICT, as the old release bills it, so concept quotes from before the
  release start counting mixed about 10 minutes after the deploy. New documents always carry their rule, so only
  pre-release rows are ever null, and every run after the first changes nothing. A forgotten listing is harmless too:
  Hibernate adds the column and the job decides every row the same way. On PostgreSQL a run waits at most 15 s for a
  row lock (`lock_timeout`); a failure is logged, rolls the run back and the next run retries.
- Creation: `Orders.save` refuses a new document; `Orders.create(order, rule)` writes the rule with the row. MIXED:
  `create` (quote, invoice, website request and order), `createFromPurchaseOrder`, `createAuctionSettlement`,
  `duplicate`, a schedule term without source quote. The source's rule: `createInvoiceFrom` (slotfactuur included),
  `SalesAdvanceBillingService.createAdvanceInvoice`, `createCreditNote`, the later part of `SalesSplits.split`, a
  schedule term made from a quote.
- Pricing: `SalesOrderService.palletRule(order)` reads the rule by id, never from the record: a draft or copy of a
  stored document counts like it (`preview` puts the path id on the draft). A draft without id counts like the invoice
  it credits or the quote it comes from, else MIXED; `priceAll` reads every rule once. `PublicQuoteService` estimates a
  request and a new website order MIXED; a change of a stored website order (`WebOrderService.previewByCustomer` with an
  `orderId`, `validateChange`, and `changeByCustomer` through `orderFacts` and `replaceOrder`) passes that order as
  `changing` and is priced by its rule (`salesOrders.palletRule`) and its kept prices, so the estimate, the stored change
  and its ordered terms agree (a website order from before the release stays strict). `PricedOrder.Totals`:
  `palletRule`, `palletsAutomatic`, `autoPallets` (`items` {productId, cartons, layers}, `heightCm` with the 14.4 cm
  base, `weightKg`, one decimal). Freight bills `palletsManual > 0 ? palletsManual : palletsAutomatic`; the PDF and
  portal line column counts the automatic pallets carrying the product.
- `SalesLineAvailability.reconcilePallets` (save, Overnemen) and `prunePallets` renumber pallets still called
  "Pallet n" after they drop one; own names stay, and a layout nothing was dropped from is never rewritten. Factuur
  maken (`createInvoiceFrom`) does not reconcile: it copies the quote's layout and a layout the lines outgrew (an
  omdoos content changed after acceptance) is refused by name as before ("Er staan meer dozen van X op pallets dan op
  de offerte", "X staat nog op een pallet; …"), so an invoice never bills fewer hand pallets than its quote unannounced.
- The open per-pallet freight names its cause: "Pallets niet te berekenen voor X: vul de omdoosmaten in" while a size
  is missing, else "X past niet binnen het gekozen palletprofiel, de hoogte of het gewicht: kies een ander palletprofiel
  of een hogere maximale pallethoogte". It is raised only on a concept that was never sent (status CONCEPT, `sentAt`
  null); a document that left concept keeps the validation it had, its freight shown and its transport saves accepted.

## Pallethoogte per document: standaard 260 cm (2026-11-10; 220 van 7 tot 10 november)

Emre, 2026-11-10: "260 cm standaard": a europallet is loaded at most 2,60 m high, the pallet itself included. (On
2026-11-07 he had asked for 220 after comparing the catalogue with Palletberekening; three days later he chose 260
again.) The standard pallet is a europallet 120 × 80 cm, max. 260 cm high with the 14.4 cm wood, 700 kg
(`enrosed.pallet.max-height-cm`, default 260; 220 from 2026-11-07 to 2026-11-10, 260 from 2026-08-20, 180 before; no
Railway variable sets it). The wood is inside the total on every path: `PalletCalculator.fit` and `mixedPallets`
subtract `baseHeightCm` before counting layers, the reported heights add it back, a hand pallet's measured height is
validated against the same total. Every sales figure is priced live on every read (PDFs, portal, website account,
payments and the paid state, Facturen, bank matching, credit caps, advances, Odoo, website order fingerprints), so a
document keeps the height it was made with instead of following the setting:

- `SalesSettings.pallet()` (catalogue per-pallet figure and basis line, every website estimate of a request or a new
  order) is the standard. `pallet(profile, height)` gives a document its own stored height; a null height reads
  `LEGACY_MAX_HEIGHT_CM` = 260: only rows made before 2026-11-07 have none. `FORMER_STANDARD_HEIGHT_CM` = 220 is what
  documents made from 2026-11-07 to 2026-11-10 store without anyone having picked it. `EURO_HEIGHT_LIMIT_CM` = 260.
- New documents store the standard: `create` (staff quote and invoice, website request and order),
  `createFromPurchaseOrder`, `createScheduledPartnerAdvance`, `createAuctionSettlement`. Documents made from another
  copy its raw height, null and above 260 included: `createInvoiceFrom` (slotfactuur too), `createCreditNote`, the
  split parts, `createAdvanceInvoice` (no lines; carries its quote's).
- `duplicate` ("Nieuwe kopie") is a new concept (`copyHeight`): the standard, unless the source has a height of its
  own. No height with hand-built pallets: 260 written down. A stored 220 is the standard of its day and gives a copy
  on today's standard, unless it was set by hand (`PalletHeightPicks`). Any other height stays; on a europallet
  without hand pallets at most 260. A copy that keeps a height other than the standard writes it in its own
  DUPLICATED activity row (field `maxPalletHeightCm`, standard → kept), so the pick travels with every further copy.
- `PalletHeightPicks.of(orderId)` (SET_HERE / INHERITED / NOT_PICKED / UNKNOWN; `byHand()` = the first two) tells a
  height set by hand from one a document merely carries. It reads the document's newest `activity_log` row that names
  the field `maxPalletHeightCm` (every save that changes the height writes one, `salesChanges`, with display values
  such as "220"): SET_HERE when that change ends at the stored height, NOT_PICKED when it ends elsewhere (before
  2026-11-07 "Standaard" stored null, and the 220 job then wrote 220 without a row). With no such row the question
  goes up the chain: an invoice asks its quote (`sourcequoteid`), a Nieuwe kopie its source, found by number from its
  history step "Offerte|Factuur gekopieerd vanuit <nummer>" (`COPIED_FROM`: keep that wording); of the source only the
  rows up to the moment the copy was made (that step's `at`, an invoice's first OPGEMAAKT step) count, with the same
  test (INHERITED / NOT_PICKED). An original without a row is NOT_PICKED; a source that is gone or renumbered, or a row
  that cannot be parsed, is UNKNOWN (a copy then takes the standard, the one-time move leaves the quote alone). A
  "Standaard" tap of 7-10 November after another pick is logged as "x → 220" and so counts as picked.
- Saves: a null height keeps the document's own (`update`, so also the website intake and a customer's change,
  `updateShipping`, `preview`). The planner's "Standaard" sends the standard's number; the editor sends a height only
  when it was changed on that screen, with `?heightPicked=true` on `PUT /api/sales-orders/{id}` and `/shipping`.
  `heightAsked`: a staff save without the mark that carries 220 on a document storing another height is the echo of a
  bundle from before 2026-11-10 (it sends the height it holds with every save, and its "Standaard" meant 220), so the
  stored height stays; any other unmarked height is taken as before (such a window cannot set exactly 220 until
  reloaded). The website paths (`updateWebsiteRequest`, `updateByCustomer`) are unaffected. Validation: above the base, at most 300 cm (`validatePalletHeight`, every
  path), and `requireEuroHeightWithinLimit` in `update` and `updateShipping`: a new height above 260 cm on a
  europallet is refused with "Maximale pallethoogte van een europallet kan niet hoger zijn dan 260 cm" (409); the
  value the document already stores on a europallet passes unchanged, blok and half pallets keep 300. Not asked in
  `preview` (saves nothing) nor where a document is made from another.
- `PublicQuoteService`: a change of a stored website order is estimated on that order's height (`OrderPricing.pallet`),
  as it is stored and its ordered terms are recorded; `orderDocument` and `store` write the stored height back.
- `PalletHeightBackfill` (the 220 round's one-time move of null-height concepts) is deleted: with the standard at 260
  it had nothing left to do. An old release that still runs its copy only ever touches rows without a height.
- `PalletHeightReturn` (scheduled `pallet-height-260`: 10 minutes after the start, retried every 10 minutes only
  after a failure; `enrosed.pallet-height.return-after` / `return-every`) puts concept quotes back from 220 on the
  standard, once per database: the row `2026-11-10-pallet-height-260` in `country_policy_version` says it ran, and no
  later start moves anything (a new name for `ROLLOUT` would run it again). A quote moves only when it stores 220, is
  an untouched concept quote (`PalletCountRuleBackfill.UNTOUCHED_CONCEPT_QUOTE`: type null or OFFERTE, CONCEPT, not
  deleted, never sent, no web order, advance, invoice, split or partner snapshot) and `PalletHeightPicks.of`, asked
  per locked quote after the re-read, says NOT_PICKED. Quotes with
  hand-built pallets move too (going up cannot make a measured pallet too tall; freight follows the hand pallets).
  Everything else keeps its 220: whatever was sent, invoiced, advanced, split or ordered, every invoice (a concept
  invoice made directly on those days too), picked heights and copies of them, deleted documents. It locks the
  candidates (`select … for update`), re-reads the test in a new statement, then updates; on PostgreSQL with
  `lock_timeout` 15 s (a held row: nothing moves, no marker, next run). Each moved quote gets one history step
  ("Max. pallethoogte 220 → 260 cm", actor Systeem, type VRACHT_INGEVULD because no rule reads that type; never
  OPGEMAAKT, whose date is a website request's age). The log lines start with "Pallet height:" and name, as
  "id number": the moved quotes, the concepts that keep a 220 set by hand, the ones that inherited it, and every
  other document still on 220 with its type and status. Checked on H2 (local state of 51 documents: 18 moved, 33
  identical to the cent) and its statements on PostgreSQL 17 with a concurrent save and a held lock.
  Read-only pre-check for production: `select id, number, doctype, status, sentat from sales_order where
  maxpalletheightcm = 220 and deleted_at is null order by id`.
- History: the 220 round's `PalletHeightBackfill` ran on production 2026-11-07 ~15:55 (ENR-2026-0016, a website
  request, went from 1 to 2 pallets) and wrote 260 down for untouched concepts with hand-built pallets.

## Batches (2026-10-25)

Which container's pieces are still on the shelf and at what cost; the margin of a shipped sale uses the cost of the
batches its pieces left (Emre: "the leaving batch, FIFO"). The catalogue/website price keeps following the product
card, never a batch. Contract: the round's `batches/contract.md`; code in `catalog` (core), `sourcing`/`sales`
(writers) and `inventory` (reads, start, REST).

- **Model**: `stock_batch` = pieces that entered together with one cost. `RECEIPT` (one per booked purchase-order
  line, key `purchase_order_line_id`, carries the line's `product_supplier_id`), `OPENING` ("Beginvoorraad", made only
  by the start) and `COUNT` ("Telling": found pieces, untraceable returns; one per product per day and cost). Owner
  `PARTNER` for a partner container, else `OWN`. Quantities are physical pieces; the cost is per selling unit of the
  batch (`unit_pieces` frozen at birth, same scale as `product.landedCostEur`), incl. the Enrosed kost, with the figure
  without it and the seven parts beside it. States `APPLIED` (Kostprijzen toepassen), `CALCULATED` (the container's
  calculation, refreshed at each shipment), `CARD`, `NONE`. Every change is a signed `stock_batch_move` written in the
  same transaction as its stock movement; `batch_id` null = "zonder batch" (a take no batch could give).
  `link_move_id` means, by shape: a restore of a take, a settle of an unassigned take by a later own receipt, or an
  unassigned take resolved without a batch. `pieces_in`/`pieces_left` are caches of the moves.
- **Invariant per product** (`GET /api/stock-batches/check`): Σ `pieces_left` − open unassigned pieces = Σ over every
  location of `quantity × factor + loose`. Company-wide FIFO: locations and transfers do not matter.
- **FIFO**: own Beginvoorraad first, then own batches by `received_on` (unknown first), id; partner batches only when
  the own ones are empty; the rest unassigned. A known container (damage on a container, a container sale, a partner
  settlement) pins the take to its batch first.
- **Start** ("Batches opbouwen", `POST /api/stock-batches/backfill?dryRun=true|false`, default dry run;
  `StockBatchBackfillService`): nothing writes before the start; every hook is a no-op until a `stock_batch_run` row
  exists. The dry run reads only, no locks. The real run (one transaction, 600 s) locks every product before reading its
  levels; per booked line a batch with the newest `product_cost_history` row of (product, container), kinds
  APPLY/REAPPLY/SEED only (a COPY row carries another product's cost), converted from the row's basis (per display only
  when applied per display) to today's unit, else the calculation of today. The shelf fills from the newest batch down
  (the `FifoValuer` rule): partner pieces first (usable − what its advances/settlements shipped by the run), then own
  newest first; the rest one OPENING batch at the card cost; negative locations one unassigned BACKFILL move. Older
  pieces get one BACKFILL move per batch. Each written product must hold the invariant, else the whole start rolls
  back. A second start is refused ("Batches zijn al gestart op dd/mm/jjjj."). Received-but-not-booked containers are
  listed, never batched. On production: dry run first, report to Emre, start only on his word.
- **Pause** (`POST /pause`, `/resume`, latest run's `paused_at`): every hook stops at once, stock work never fails on a
  batch bug, shipments stamp no stock cost; the drift shows in `/check`. The first response to any batch error.
- **Repair** ("Herstellen", `POST /check/repair {productIds}`, empty = every product in the check; only while active):
  too few batch pieces → found into today's Telling batch at the card cost; too many → a FIFO take of kind `REPAIR`.
- **Reset** (on Emre's word only; no button): `POST /api/stock-batches/reset` with `{"confirm":"RESET"}`, or the same
  by SQL, in this order:

```sql
begin;
delete from stock_batch_move;
delete from stock_batch;
delete from stock_batch_run;
update sales_order_line set stock_cost_eur = null where stock_cost_eur is not null;
commit;
```
  Afterwards the dry run and the start can run again.
- **REST** (`/api/stock-batches`, admin only): `GET /status`, `/products/{id}`, `/{batchId}/moves?limit=1..500`,
  `/purchase-orders/{id}` (rows in line order, live-only `costs`, `stale` = batch cost and today's calculation differ in
  cents), `/sales-orders/{id}` (the takes of the shipment per batch), `/check`; `POST /pause`, `/resume`,
  `/check/repair`, `/backfill`, `/reset`. Business refusals answer 409.

## Enrosed kost in the recognised cost (2026-11-06)

Emre: the Enrosed kost is Enrosed's own revenue, paid to nobody, so Analyses › Resultaat counts the Enrosed kost of
what was sold as revenue (Verkoop's margin, the quote and the sales line keep it as cost). `SalesAccounting
.recognizedEnrosedEur` = the Enrosed kost inside `recognizedCostEur`, signed like it (`PartnerFinancingService
.accounting` → `SalesEnrosedCost`): a line with a stamped stock cost takes the shipment's takes (value with minus without
the Enrosed kost; none on a container sale; pieces without a batch at the expected cost's part, the stamp's own rule); any
other line the part of the newest `product_cost_history` row with parts carrying exactly its cost
(`ProductCostHistoryService.enrosedParts` → `EnrosedParts.enrosedIn`, 4 decimals, the Gelande kost sheet's rule; no row
= 0, e.g. a hand-typed cost or a container sale's own lines frozen without it); a credit note that copied its invoice's
stock cost takes that shipment's part when the cost matches. Partner advances and settlements hold 0 (external cost).
The sales list preloads both reads once per request (`SalesEnrosedCost.preloadForRequest`, request-scoped
`SalesEnrosedCostCache`; `StockBatchService.saleCosts()` = every shipment's takes in one read). TICA:
`GET /api/tica/analytics` days carry `enrosedCostEur`, the same rule on the product's current cost.

## Voorraadradar / stock radar (2026-10-28, on production since 2026-10-31)

A personal bot for Emre: watches the white iPhone Duo in 256 GB (Apple part
`MK2D4ZD/A`, EAN 0195951406084) and 512 GB (`MK2F4ZD/A`, `StockRadar.Model`; a
shop's keys get `-512`; Amazon.com.be lists no 512 GB) at BE/NL shop pages and pushes +
mails him when a shop can deliver or hand it over within
`enrosed.stockradar.max-delivery-days` (3). Package `be.enrosed.stockradar`.

- **Shops and parsers** (`StockRadarStores`): Apple Store Brussel (R486) via
  `/be-nl/shop/retail/pickup-message` (the `fulfillment-messages` endpoint
  answers 541 in the BE store), Apple online via `/be-nl/shop/delivery-message`,
  MediaMarkt BE 2253422 (403 to plain HTTP from Railway → headless shell, the SSR Apollo object
  `"CofrDeliveryFeature:Media:nl:<article>":{` → `deliveryStatus`; loose page
  texts also carry recommended products and a translation table, never read
  them), MediaMarkt NL 1914026 (Akamai 403 over HTTP → headless shell, flaky),
  Coolblue BE/NL 986123 (JSON-LD offer `availability` + the `"promise"` field;
  the page's translation table always holds "Morgen bezorgd". Store stock, "Nog
  sneller op te halen in N winkels", is `storeAvailabilities` per stock location:
  `OnStock` counts as available today, whatever the online promise says, and a
  store joining an available shop alerts again; names in `CB_STORE_NAMES`, from
  the store pages), Krëfel 51008017
  (answers 500 to curl and to the headless shell → full Chromium after a
  home-page visit; only the block from the title to "Variaties"), Vanden Borre
  `iphone-duo-256gb-sw` (client-rendered → shell; only up to the availability
  block, the accessories below use the same words), Amazon.com.be B0HJB16BGD
  (`#availability` + delivery block), bol.com products 9300000386857691 and
  …686 (its robots.txt closes search and leaves product pages open; only the
  order block counts, the structured data holds every variant). Amazon.nl does
  not sell it. A page
  without a readable status is an error on the screen, never "not available".
- **Verdict**: `Observation(inStock, deliveryBy, statusText, priceEur,
  foundUrl)`; available = inStock and (no date or date ≤ today + N). Pre-orders
  with a later date are "Later leverbaar", not available (Emre, 2026-10-28).
- **Timing** (`StockRadarService`): one `@Scheduled` tick every
  `enrosed.stockradar.every` (10 s) looks what is due; every shop runs once a
  minute, up to 15 % later and never earlier (Emre, 2026-10-31: faster gets
  refused, Apple answered 541 to a 20-second rhythm). A failing shop is asked
  again at 2x, 4x, 8x its rhythm, at most ten minutes. `STOCK_RADAR_SPEED`
  multiplies all of them. HTTP shops run in parallel on virtual threads;
  browser shops due within 30 s share one round on one background thread
  (shell batch, then full-Chromium batch), not one launch per shop. Models that
  read one page (Apple's two endpoints, bol's search) are asked together in one
  request, so Apple gets no more traffic for the second model. "Nu
  controleren" keeps the same rule: a shop asked under a minute ago is skipped.
- **Browser** (`StockRadarBrowserFetcher`): runs beside the live ERP, so it gets
  an empty environment and reaches the shops' own domains only. A watchdog
  closes the driver after 60 s + 60 s per page: `page.content()` has no timeout.
- **State**: table `stock_radar_store` (one row per shop key; migration
  `docs/migrations/2026-10-28/stock-radar-postgresql.sql`). Only a flip of
  `available` notifies (push to the devices of `enrosed.stockradar.notify-user`
  = emre through `StockRadarPushSender`, never to anyone else's; mail to
  `enrosed.stockradar.mail-to` = emre@entego.be through `StockRadarMailSender`,
  a direct Brevo POST). An "available" flip stays unsent until one door takes
  it (`notified_at` older than `changed_at`) and is sent again each round. On
  the very first start, with an empty table, one
  "staat aan" push and mail go out (`greetOnce`). Push needs a device: the
  native app has no web push, so the installed web app on the phone must have
  Meer > Meldingen on.
- **REST** `/api/stock-radar` (`GET`, `POST /check`, `POST /{key}/check`,
  `POST /test-push`, `POST /test-mail`): only the notified user gets an answer,
  anyone else a 404; the frontend lists the page for that user only.
- **Switches**: `STOCK_RADAR_ENABLED` (default true on deployed environments,
  false in dev), `STOCK_RADAR_PUSH_ENABLED`, `STOCK_RADAR_MAIL_ENABLED`,
  `STOCK_RADAR_BROWSER_ENABLED`, `STOCK_RADAR_SPEED`. The Dockerfile installs
  the full Chromium next to the headless shell for this.
- **CLI check without the app**: `java -cp 'target/quarkus-app/lib/main/*:target/quarkus-app/lib/boot/*:target/quarkus-app/app/*' be.enrosed.stockradar.StockRadarProbe [keys] [--no-browser]`.

## Klantprijzen and the priced catalogue (2026-11-06)

Emre: "prijzen maken per klant ... en dan als je exporteert VOOR een klant, dat dan die prijzen
genomen worden", plus one-off adjustments per PDF and a record of which customer saw which price.

- **Klantprijzen** (`sales/application/CustomerPriceService`): one price per customer and product
  (`customer_product_price`, unique on the pair), EUR excl. VAT on the product's own sales unit
  (per display when sold per display), exactly the basis of the catalogue price. At most 2
  decimals (refused, never rounded), 0 < price < 1 000 000, optional `valid_until` (null: until
  changed; a new end date in the past is refused) and note (300). Each row remembers the basis it
  was set on (`sales_unit`, `pieces_per_unit` only for a display, `unit_key`) and the catalogue
  price of that moment. `catalog/domain/PriceBasis` is the one rule for "still the same basis":
  the sales unit and, per display, its pieces; the unit's name does not count.
- **Status** per row: PRODUCT_GONE (product deleted), BASIS_CHANGED (the amount would mean
  something else now; typing the price again confirms it on the new basis), EXPIRED, ACTIVE.
  Only ACTIVE rows apply; the others are kept.
- **History** (`customer_product_price_history`) is append-only: one row per set, change of
  price, basis or end date, and removal (null price), with the previous price and the source
  CUSTOMER_SCREEN, CATALOG_EXPORT ("Ook bewaren als prijzen van {klant}" in the export popup) or
  CUSTOMER_DELETED. A note-only edit writes no history row. Each save with a change writes one
  Logboek entry on the customer ("Klantprijzen bijgewerkt (n)" / "Klantprijzen bewaard vanuit de
  catalogus (n)", fields "Klantprijs · SKU", "Geldig tot · SKU"; at most 40 shown).
- **REST** (admin): `GET /api/customers/{id}/prices` (the rows with sku, name, catalogue price at
  set and now, status, updatedBy as display name), `PUT /api/customers/{id}/prices`
  `{source, changes:[{productId, priceExclVat|null, validUntil?, note?, basis?, piecesPerUnit?}]}`
  (patch: only the listed products change, null removes, a gone product can still be removed;
  answers the full list),
  `GET /api/customers/{id}/prices/history?productId=` (newest first, 200). The save locks the
  customer row. Within a change, `validUntil` and `note` are tri-state: left out of the JSON they
  keep what is stored (a new price starts without them), sent as null they clear, sent with a
  value they replace. Presence is read in `CustomerPriceResource.ChangeLine` (Jackson calls a
  setter only for a property the body names) and reaches the service as `Change.validUntilSent`
  / `noteSent`; the price itself is never optional (null removes). So a client that does not
  know a field (the export popup's "Ook bewaren" knows no note) must leave it out, not send null.
- **Stale unit** (`CustomerPriceBasisChangedException`): a change may name the unit its price was
  typed against, `basis` PIECE|DISPLAY and `piecesPerUnit` (per display; null per piece), as the
  catalogue's one-off prices do. When that no longer matches the product (`PriceBasis`), the whole
  save is refused before anything is written: 409 `{code: CUSTOMER_PRICE_BASIS_CHANGED, message,
  productIds}`, the message naming the SKU and the unit it is sold per now. Without a basis (older
  screens) the price is stored on the product's unit at save time, as before; a removal needs none.
- **Deletion**: `CustomerPriceDeletionListener` removes a deleted customer's rows inside the
  deletion and writes CUSTOMER_DELETED history.
- **Priced catalogue for a recipient** (`CatalogExportService`): the request may name
  `customerId` (a stored customer; unknown: 404 "De gekozen klant bestaat niet meer") or, without
  one, `recipientName` (a fair lead; trimmed, max 120, no stored prices), `pricesValidUntil` and
  `priceOverrides` [{productId, priceEur, onRequest, basis PIECE|DISPLAY, piecesPerUnit}]. All
  optional, so an older studio prints catalogue prices as before. Precedence per product:
  one-off (this PDF only) > the customer's ACTIVE price > the catalogue price
  (`computedSalesPriceEur`) > "Prijs op aanvraag". One validation in `model()` serves preflight
  and export: overrides or a validity without prices, a product not in the selection, a
  duplicate, a price outside 0..1 000 000 or with more than 2 decimals, a price with "op
  aanvraag", or a basis that no longer matches the product refuse with 409
  `{code: CATALOG_PRICES_INVALID, message, priceIssues:[{productId, code, message}]}`. An
  override equal to what the product would print anyway is dropped. Warnings do not refuse
  (`priceWarnings` in the preflight): BELOW_COST (one-off or customer price under
  `landedCostEur`), DISPLAY_WITHOUT_PIECES (a display sold per display without its piece count
  still prints "Prijs op aanvraag" and its row says so; only older data, the product form now
  requires the count), CUSTOMER_PRICE_EXPIRED,
  CUSTOMER_PRICE_BASIS_CHANGED, VALIDITY_PAST, CUSTOMER_PRICE_ENDS_BEFORE_VALIDITY (a printed
  customer price whose end date lies before the PDF's "Geldig tot": the PDF would promise it
  longer than agreed).
- **Preflight** adds `prices` (one row per selected product while prices are on: basis,
  piecesPerUnit, unitKey, catalogueEur, customerEur, printedEur null for "op aanvraag", source
  CATALOGUE|CUSTOMER|ONE_OFF|ON_REQUEST; an empty list with prices off), `priceWarnings` and
  `recipient`. `ready` still means translations only. The studio refuses a recipient or
  one-off prices when `prices` is missing (an older backend would silently drop them), so
  deploy the backend first.
- **Renderer**: every printed amount comes from the model's `PriceBook` (`catalogPrices(product,
  amount)`); the display/piece wording is unchanged. With a recipient and prices: "Prijslijst
  voor {klant}" on the brochure cover (between the rule and the title) and under the compact
  masthead title, the recipient in the compact running header, "Uw prijs" instead of
  "Referentieprijs", the basis "Prijzen voor {klant} in EUR per stuk/per vermelde eenheid,
  exclusief btw en levering." in the overview footnote and on every compact footer, and
  "Geldig tot {datum}" with a validity. Without prices: "Samengesteld voor {klant}". Names are
  printed as typed, cut at a word past 80 characters (48 in the running header). New CATALOG
  keys, 9 languages: `catalog.pricelist.for|yourprice|basis|basisunits|validuntil`,
  `catalog.recipient.for`.
- **File name**: `enrosed-prijslijst-<slug>-<lang>.pdf` with a recipient, else
  `enrosed-brochure-<lang>.pdf` / `enrosed-catalogus-<lang>.pdf` (the studio's names). Slug
  (`Recipient.fileSlug`, the studio builds the same): NFD, marks dropped, lower case, every run
  outside a-z0-9 one dash, at most 60 characters, no dash at the ends, "klant" when empty.
- `/api/catalog/export` also produces JSON, so a refusal (404 unknown customer, a family page
  with too much text) reaches the studio's blob request as a readable body; the PDF itself is
  always sent as application/pdf.
- **Compression** (2026-11-07, Emre: "pdf compresser toggle om pdf rond 10 mb te krijgen zet dit
  standaard aan"): `?compress=` on `/api/catalog/export`, default true; the studio's "PDF verkleinen"
  sends false when it is off. `CatalogExportService.export(request, compress)` runs the out-port
  `CatalogPdfCompression` (`PdfCompressor`, PDFBox) after the render and before the journal, so the
  journal's size and SHA-256 are the PDF that leaves. Every image XObject (pages and the forms they
  draw, each once) becomes at most 1400 px on its longest side, a JPEG of quality 0.80 with its
  alpha kept as a soft mask; one that would not get smaller, a stencil or < 8 bit stays. The PDF
  renderer embeds the transparent catalogue photos losslessly at print size (PdfImageEncoder, up to
  2400 px), which made the whole range 65-70 MB: measured on his brochures of 2026-10-26/30 69.7 to
  10.4 MB (8 s) and 65.7 to 9.9 MB (6 s), no visible difference at 200 dpi. Any failure hands back the
  original PDF (logged).
- **Export journal** (`catalog/application/CatalogExportJournal`, tables `catalog_export` and
  `catalog_export_line`): every export with prices on or a recipient, written after a
  successful render in its own short transaction (a separate bean, because the render can take
  minutes and runs outside one); a failed write fails the export. It keeps who (username and
  display name) and when, the customer id with the name as printed (or only the typed name),
  layout, language, includePrices, the validity, counts (products, customer prices, one-off
  adjustments incl. "op aanvraag", op aanvraag), the file name, size and SHA-256 of the PDF, and
  one line per product in selection order (sku, name in the PDF's language, sales unit,
  printed/catalogue/customer price, source). A stored customer with prices also gets one Logboek
  entry (action `CATALOG_EXPORTED` on CUSTOMER, "Catalogus met prijzen gemaakt · 24 producten,
  3 klantprijzen, 2 eenmalig · Handelscatalogus NL", file name and validity as fields). The ERP
  says "gemaakt": it cannot know whether the PDF was sent. REST (admin):
  `GET /api/customers/{id}/catalog-exports?limit=20` (newest first, max 100) and
  `GET /api/catalog/exports/{id}` (the header fields plus `lines`). A deleted customer's
  exports stay, under the name they were made for.
- **Schema**: `docs/migrations/2026-11-06/customer-prices-and-catalog-exports-postgresql.sql`,
  rerunnable, new tables only, copied in the Dockerfile and listed in the migration script before
  `sales-line-price-source-postgresql.sql` (round 3b below).

### Klantprijzen on quotes, invoices, the portal and the website (round 3b, 2026-11-06)

Emre: "Klantprijzen moeten ook op offertes en facturen kunnen alsook DEFAULt kunnen nemen. Je moet
ook wel altijd subtiel default prijs tonen langs onze kant" and "Ingelogde klanten met andere prijs
moeten dan andere prijs zien natuurlijk."

- **One lookup, one day**: `CustomerPriceService.activeFor(customerId, products)` gives the ACTIVE
  prices (not expired, still on the product's basis) by product id, on `LocalDate.now(Europe/
  Brussels)` for every caller (Railway runs in UTC). Quotes, invoices, the portal list and the
  website all go through it; the catalogue export reads the stored rows itself (same zone).
- **Pinned, never looked up live**: a customer price becomes a line's `unitPriceEur` when the line
  is added, with `sales_order_line.pricesource` (`SalesOrderLine.priceSource`, plain varchar,
  `docs/migrations/2026-11-06/sales-line-price-source-postgresql.sql`, rerunnable `add column if not
  exists`, Dockerfile COPY and script line after the round-3 file): CUSTOMER (pinned agreed price),
  STANDARD (staff chose the catalogue price; no unit price, follows the catalogue, never pinned) or
  MANUAL (typed); null = every older line and a line on the catalogue by default. Existing lines
  never change by themselves: a sent quote, its invoice and every document past concept keep their
  prices whatever the customer price does later. `SalesPricingCalculator.unitPriceFor` knows
  nothing of customer prices; a pinned price is a manual price, so the LINE/ORDER tiers and the
  extra discount apply on top exactly as on a typed price (Emre told).
- **Where it pins** (`SalesOrderService.pinsCustomerPrices`): a regular quote or invoice with a
  customer, PRODUCT or ORDER markup, a website request or order included once staff save it (a
  product staff add gets the agreed price; the quote link offers it too). Never a credit note, a
  partner deal, CONTAINER_COST, a split part or an advance invoice. The website's own intake of a
  request (`updateWebsiteRequest`) and the customer's `updateByCustomer` never settle: their lines
  keep the prices and sources the website path gave them (below).
- **Rules** (`SalesLinePrices.settle`, on every staff save and preview, never on the customer's
  `updateByCustomer`): a new line (no stored line with its id) without a price and without a source
  gets today's agreed price as CUSTOMER; a client without the field (an older screen) keeps the
  stored source while it keeps the price, and a price it changes becomes MANUAL (or null when
  cleared); STANDARD with a price is MANUAL; MANUAL without one is null; CUSTOMER without a price
  takes today's agreed price ("Klantprijs gebruiken"), else falls back to the catalogue; CUSTOMER
  with a price stays CUSTOMER only when it is today's agreed price or the price already pinned on
  that line, otherwise it was typed over (MANUAL); that check runs on every document, so a website
  line keeps the CUSTOMER the website path gave it. An existing CUSTOMER line that arrives as
  CUSTOMER without a price (an older screen clearing the price field) goes back to the catalogue.
  A concept that changes customer re-pins every CUSTOMER line, on every document, and on a document
  that pins also every line on the catalogue by default (null source, no price), to the new
  customer's agreed price, or back to the catalogue (null, null) when they have none; STANDARD and
  typed lines stay. (The contract named only the CUSTOMER lines; the default lines go along so A ->
  B -> A gives A's prices back.) A credit note never carries a source. An unknown source is refused.
  The Logboek names a change as "Prijsbron · Product n" (klantprijs / standaardprijs / eigen prijs).
- **Also pinned**: lines a container sale at sales prices starts with (`createFromPurchaseOrder`,
  not at cost), and products a customer added through the quote link when staff adopt the proposal
  (Overnemen; kept lines keep price and source).
- **Copies keep it**: invoice from quote and slotfactuur, Overnemen, delivery weeks. A split part
  keeps CUSTOMER only. "Nieuwe kopie" is a new concept: its CUSTOMER lines take today's agreed price,
  or the catalogue once that price ended or its unit changed, and on a copy that pins its catalogue
  lines take the agreed price as a new line would (`withTodaysCustomerPrices`).
- **Staff view** (only `GET /api/sales-orders/{id}` and the single-document answers built by the
  same view, preview included; never the list, a PDF, the portal or the website): each priced line
  carries `priceSource` and two read-only figures, `standardUnitPriceEur` (what the line would cost
  on the catalogue today, `unitPriceFor` without a manual price) and `customerUnitPriceEur` (today's
  agreed price, null without one). They are filled for every line with a source, and on a live
  concept that pins (not archived: a quote is archived once invoiced) also for a line without one
  whose product has an agreed price; all three are `NON_NULL`, so a document without either answers
  byte for byte as before (`priceForStaff`, `SalesPricingCalculator.PriceHints`).
  `order.lines[].priceSource` is `NON_NULL` too.
- **Product side** (admin): `GET /api/products/{id}/customer-prices` ->
  `[{customerId, customerName, priceExclVat, validUntil, status}]` by customer name (404 for a
  gone product), for the "n klantprijzen" chip beside the sales price.
- **Quote link** (Emre's Q4): the portal's add-products list (`PortalResource.catalog`) offers the
  customer's agreed price where the quote pins (`customerPricesFor`), so an adopted line costs what
  the customer saw. The portal page itself shows the quote's own (pinned) prices as always.
- **Website**, see "Customer logins" and "Website orders" above: a logged-in customer sees and
  orders at their agreed price; anonymous visitors and customers without one keep the public list.
- **Checked live** (2026-11-06, dev build on H2 and the packaged jar on PostgreSQL 17): documents
  made before (a sent quote, an issued invoice and credit note, of a customer with agreed prices)
  answer byte for byte in the staff GET and the portal, their PDFs have the same text and pages;
  the anonymous configuration and estimate are byte for byte origin/main's, price switch on and
  off; the migration ran twice and Hibernate changed nothing on start. Not walked live: split
  parts, partner deals and advance invoices (skipped by rule only).

## Klantstatus en contactmomenten (CRM, 2026-11-07)

Emre wants the customer list to work as a small CRM: where a customer stands and what we already
did for them (mailed, called, spoken to, catalogue or sample sent).

- **Status**: `Customer.crmStatus` (`customer.crm_status`, varchar(24), plain code, no enum or
  CHECK constraint) is one of `CustomerCrmStatus` NIEUW / IN_GESPREK / ACTIEF / SLAPEND / VERLOREN.
  `POST /api/customers` takes a known status or none (= NIEUW); `PUT` takes a known status or null
  (= keep the current one); anything else is refused ("Onbekende klantstatus: X"). Existing
  customers stay null on purpose (the frontend shows "Geen status"); nothing is derived from orders.
- **Contact moments**: `Customer.touchpoints` = `[{kind, at, by}]`, one per kind, as rows of
  `customer_touchpoint` (`CustomerTouchpointEntity`, unique on customer and kind, no foreign key).
  Known kinds: GEMAILD, GEBELD, GESPROKEN, CATALOGUS_GESTUURD, STAAL_GESTUURD; any code matching
  `^[A-Z][A-Z_]{1,31}$` is stored, anything else refused ("Onbekend contactmoment: X"). The client
  sends only kinds: a new kind gets `at = now` and `by = CurrentActor.name()` (the canonical staff
  username, "systeem" outside a request), a kind the customer already has keeps its first moment,
  a kind left out loses its row; `touchpoints: null` on PUT keeps everything, `[]` clears them.
  The column keeps the username like every audit column; what a client receives as `by` is the
  display name (`CustomerService.named` via `AdminIdentityProvider.displayNameFor`, "Emre",
  "Systeem"), the same way the klantprijzen history names people.
  `CustomerAdapter.findAll` loads all customers in one query and all touchpoints in a second one
  (grouped by customer), never per customer; `findById` and `save` load the customer's own rows,
  and `save` locks the customer row (`PESSIMISTIC_WRITE`) first so two saves ticking the same
  kind at once cannot both insert it; `deleteById` removes the rows before the customer.
- **Gehanteerde prijzen** (`GET /api/customers/{id}/applied-prices[?productId=]` and `/{productId}`,
  `CustomerAppliedPriceService`): one JPQL projection over the lines (never the eager order graph),
  deleted, cancelled and credit documents left out, a quote that became an invoice counted once as
  the invoice. A line that followed the catalogue stored no price: `lastPriceExclVat` /
  `unitPriceEur` are null then (`followsCatalogue: true` on an entry), the row's "last" line is the
  newest priced line on a document past concept, and min/max skip catalogue lines. Entries carry
  `lineId` so two lines of one product on one document stay apart.
- **Compatibility**: the 20-argument `Customer` constructor stays (null status, null touchpoints =
  unknown) for `SalesMapper`, `PublicQuoteService`, `DemoDataLoader` and `LoginRequestService`;
  `withDeliveryAddress` / `withDelivery` and `fillMissingAddress` carry both fields through.
- **Logboek**: a status change logs as "Status" (Nieuw / In gesprek / Actieve klant / Slapend /
  Verloren), a change in contact moments as "Contactmomenten" with the joined Dutch labels (Gemaild,
  Gebeld, Gesproken, Catalogus gestuurd, Staal gestuurd; an unknown kind by its code), in the fixed
  order of the kinds so a reordered row never logs. The summary stays "Klant bijgewerkt".
- **Migration**: `docs/migrations/2026-11-07/customer-crm-status-touchpoints-postgresql.sql`
  (rerunnable `add column if not exists` + `create table if not exists`, no rows written), with its
  Dockerfile COPY and script line after the 2026-11-06 files. Not yet run against a live database.

## Partnerportaal (Verhoeven), 2026-11-10

Emre's partner Verhoeven (the person is Frans) gets a portal of its own: a login `verhoeven`
with a simple tile screen to report what sold at the auction ("de klok") or directly from his
site and what he bought back on the clock. Staff invoice those meldingen ("Veilingverkopen")
whenever they want. Package `be.enrosed.partnerportal` (domain / application / adapter in+out).
This section grows per build stage; it holds what is built so far.

### Authentication (stage 1)
- **One header for both kinds**: the partner signs in with the same HTTP Basic header as staff,
  first `verhoeven:<password>` on `POST /api/auth/session`, then `verhoeven:epp1_...` on every
  call. The frontend's `Auth.login` therefore works unchanged.
- **`PartnerIdentityProvider`** (`adapter/in/security`) implements
  `IdentityProvider<UsernamePasswordAuthenticationRequest>` with `priority()` 1100. The verified
  default (and the staff provider's) is 1001 = SYSTEM_FIRST+1; Quarkus runs providers by
  descending priority, a null item passes to the next provider and any failure ends the whole
  authentication. Its steps, in this order:
  1. Canonicalise the username (`ActorRef.canonicalUsername`: trimmed, lower case, so iOS's
     "Verhoeven" works). A configured staff name (`AdminIdentityProvider.isStaffUsername`, parsed
     once) returns `Uni.createFrom().nullItem()` synchronously: no worker thread, no database.
     Staff sign-in is byte for byte what it was.
  2. Anything else runs on a worker thread (`context.runBlocking`) in
     `PartnerAuthenticator`, each step in `QuarkusTransaction.requiringNew()`: no `partner_login`
     row returns null, so the staff provider fails as it always did for an unknown name.
  3. An existing login: inactive, customer gone or no longer `partner`, wrong password or key,
     a database error: every problem becomes `AuthenticationFailedException` (401), never a 500.
     A credential starting with `epp1_` is looked up by SHA-256 in
     `partner_session` of that login (not expired; `last_seen_at` written at most every 15
     minutes) and gives `partnerAuth=SESSION`. Otherwise it is a password: first
     `PublicFormRateLimiter.checkKey(PARTNER_LOGIN, "USERNAME_IP", username|network, 20)` (20 per
     15 minutes per network, `ClientIdentityResolver` on the request's RoutingContext, the
     website forms' rule) and `checkKey(PARTNER_LOGIN, "USERNAME", username, 100)` over all
     networks, only on this path and only for an existing partner name, then bcrypt outside any
     transaction; `last_login_at` is written; `partnerAuth=PASSWORD`. Every password attempt
     counts, right or wrong, so a stranger who knows the name only exhausts the bucket of their
     own network (or needs many networks for the shared 100). A password that was not checked
     (limit reached, no bcrypt slot within 2 s) is `PartnerSignInUnavailableException`: 429 or
     503 with Retry-After and a Dutch message through `PartnerSignInUnavailableMapper` (Quarkus
     hands a non-AuthenticationException failure of an identity provider to the REST exception
     mappers), never a 401 that would tell Frans his password is wrong.
- **Identity**: principal = the stored username, role `partner` only, attributes
  `displayName` (the login's name, "Frans"), `partnerCustomerId`, `partnerLoginId`,
  `partnerAuth`, `partnerSessionId` (session path only). Names in `shared.security.PartnerIdentity`.
  `CurrentActor` therefore names Frans in the activity log for free.
- **`AuthResource`**: `/me` and `/session` allow `admin` and `partner`. A staff answer is the old
  code path untouched. A partner answer has the same shape with `roles:["partner"]` and
  `phone:null` and never calls `StaffContactService` (which 404s for non-staff). Partner
  `/session` only on the password path (a session key gets 403 "Meld opnieuw aan met je
  wachtwoord", so a leaked key cannot extend itself); it goes through the `PartnerSessionIssuer`
  port, so `shared.security` does not depend on the portal.
- **Sessions** (`PartnerSessionService`): `epp1_` + 32 random bytes base64url (48 characters),
  only the SHA-256 hex stored, TTL `enrosed.partner.session-ttl-days` (default 90, 1..365, env
  `ENROSED_PARTNER_SESSION_TTL_DAYS`, nothing to set on Railway), at most 5 per login (the oldest
  go). Unlike staff `enr1.` keys each session is revocable. `POST /api/partner-portal/logout`
  deletes the session the key belongs to (204). All sessions of a login go on a new password, a
  changed username, deactivation, and when `CustomerService.update` turns the customer's partner
  flag off (`CustomerPartnerListener`, implemented by `PartnerPortalCustomerPartnerListener`).
  A sign-in that checked a password a reset replaced while it ran gets no session: the password
  identity carries `partnerPasswordStamp` (SHA-256 of the hash it was checked against) and
  `issue` compares it with the login's hash under the partner lock that the login write also
  takes (401 otherwise); a session issued before the reset commits is deleted by it.
  `last_login_at` is written by a one-column UPDATE, so it can never write an old hash back.
- **Passwords** (staff type them in Instellingen › Partnerportaal; the owner's choice, unlike the
  website logins where staff never see one): 10..72 characters, printable ASCII 0x20-0x7E only
  ("Gebruik letters zonder accenten, cijfers en leestekens": the browser builds the Basic header
  with `btoa`), bcrypt cost 12 behind a semaphore of two (`PartnerPasswords`), never returned or
  logged.
- **Deletion**: `PartnerPortalCustomerDeletionListener` refuses "Deze klant heeft veilingverkopen"
  when any melding exists, else deletes the login, its sessions and the settings row.
- **Public resources**: a partner Basic header is now valid, so a `@PermitAll` resource called with
  it runs as that partner; none of them reads `SecurityIdentity`, so it sees what an anonymous
  visitor sees.

### Data model
Migration `docs/migrations/2026-11-10/partner-portal-postgresql.sql` (registered last in the
Dockerfile COPY list and `scripts/run-postgresql-schema-migrations.sh`), six tables, every
column named in its entity, non-id columns nullable, no foreign keys, kinds as plain varchar,
named constraints identical in `@Table` and the migration:
- `partner_login` (unique customer_id, unique lower-case username), `partner_session` (unique
  token_hash, index login_id), `partner_portal_settings` (PK customer_id; null percents read as
  4.50 / 5.50, null location as MAIN; the row is also the partner lock, taken with
  `PartnerPortalRepositories.Settings.lockOrCreate`, which creates it in its own transaction when
  missing and then locks it FOR UPDATE in the caller's).
- `partner_sale_report` (the melding VV-<id>; unique (customer_id, client_ref)),
  `partner_sale_report_line` (one row per part: entry_no, SALE|BUYBACK, purchase_order_id null =
  own stock, money 19,2, unit prices 19,4, mirrors_line_id, stock_movement_id, invoice_id),
  `partner_sale_invoice_part` (append-only: which part went on which document; a document with
  rows here is report-backed).
- **Open-part rule**, written once in `PartnerOpenParts`: a part is open when its melding is not
  voided and it was never invoiced, or its document is gone, trashed, or GEANNULEERD / AFGEWEZEN /
  VERLOPEN (a concept still holds its parts). Everything that asks "open?" uses this class.

### Endpoints so far
- Partner, `@RolesAllowed("partner")` (`PartnerPortalResource`, the customer id only from the
  identity): `GET /api/partner-portal/overview` (partner, costs that apply, the first staff
  contact with a phone, `stock` = active products with quantity > 0 at the settings location in
  sales units, sorted by family then name, `recentSales` = products of this partner's non-voided
  sale parts sold in the last 60 days, `reports` = 10 newest, `serverDate`); `GET /reports?before=&
  limit=` (newest first, limit 1..100, `more`); `GET /products/{id}/photo?size=small|medium&v=`
  (404 unless the product is in this partner's current stock or meldingen); `POST /logout`. Never
  a price, cost, supplier or container field of Enrosed's.
- PartnerReport (`PartnerReportReader`): parts merged back per entry (sale and buyback), totals
  (sale gross, commission, transport, extra, sale net, buyback = terugkoopkost, net = sale net -
  buyback), status OPEN / PARTLY_INVOICED / INVOICED / VOIDED from the open-part rule, `canUndo` =
  OPEN and no buyback of another live melding mirrors one of its sale parts. Rows of another
  line_type are ignored.
- Photos: `catalog.application.SalesPhotoService` ports the frontend's `salesPhoto` rule (website
  lead, else the inherited family photo, else the first). An inherited photo is streamed from the
  family's stored small rendition for `small` (no decode) and rendered from its large file for
  `medium`; an own photo goes through `PhotoDeliveryService.render`. `photoVersion` = 16 hex of a
  SHA-256 over photo id, storage key and size, so another lead or file changes the URL; ETag =
  version + size, `If-None-Match` answers 304 without decoding, `Cache-Control: private,
  max-age=86400`.
- Staff, `@RolesAllowed("admin")` (`PartnerPortalAdminResource`): `GET /api/partner-portal-admin`
  (partner customers plus any customer that still has a login, `partner:false` then; per row the
  login without any secret, the stored settings with the defaults beside them and the applying
  location name, and `unshippedSettlements` = issued partner settlements of this partner (status
  UITGEREIKT / VERZONDEN / BEKEKEN / BETAALD, `goodsShippedAt` null) that are not report-backed);
  `PUT /{customerId}/login` `{username, displayName, password|null, active}` (username
  `[a-z0-9._-]{3,64}` after lower-casing, unique, never a staff name; a first login needs a
  password; a customer that is no partner can only have its login switched off);
  `PUT /{customerId}/settings` `{auctionCommissionPct|null, auctionTransportPct|null,
  locationId|null}` (0..50, null = default; an active existing location). Activity
  `PARTNER_LOGIN` CREATED/UPDATED ("Login aangemaakt · Wachtwoord ingesteld", "Gedeactiveerd",
  never the secret) and `PARTNER_PORTAL_SETTINGS` UPDATED; both, and `PARTNER_SALE_REPORT`, count
  under SALES in `ActivityCategory`.


### Meldingen: money, stock, withdrawal (stage 2)
- **Money** (`PartnerSaleMoney`, the only place it is computed; the frontend's `partner-money.ts`
  does the same in cents): sale gross = the typed total (2 dp), else round2(quantity x average at
  4 dp); stored unit price = total / quantity at 4 dp. Commission and transport = round2(gross x
  pct / 100) per entry with the melding's snapshotted percents (the partner's settings on
  AUCTION, 0 on DIRECT). Extra kost PCT (0..100, 4 dp) per entry the same way; AMOUNT (2 dp, the
  whole melding) spread over the sale entries by gross with `PartnerSettlementLedger.allocate`
  (320 + 61 with 12.50 gives 10.50 / 2.00). net = gross - costs; an entry with net < 0 is
  refused ("De kosten zijn hoger dan de opbrengst"). A buyback has only its gross (terugkoopkost),
  net = -gross. An entry split over sources: quantity split, then gross and each cost allocated by
  quantity (residual on the last part), each part's net = its gross - its costs.
- **Locks**, every create and withdrawal, one transaction, this order: the partner row
  (`Settings.lockOrCreate`), every container of the partner ascending
  (`PurchaseOrderService.lockForPartnerSettlement`), the products ascending
  (`StockService.lockProduct`). Open parts, the ledger and the batches are read after them.
  `client_ref` (unique per customer): the partner lock makes a retry wait and find the melding
  (200, nothing booked twice); a unique violation is answered the same way from a new transaction.
- **Allocation of a sale** (`PartnerSaleReportService.planSale`, `PartnerContainerCapacity`):
  refused beyond the level at the partner's location ("Er liggen nog maar n stuks van X. Bel
  Enrosed (tel) om de voorraad bij te werken."; staff get "... op Magazijn. Werk eerst de voorraad
  bij."). Candidates: this partner's containers with `isStockBooked()`, received at the location
  (receivingLocationId = it, or null when it is MAIN), oldest `receivedOn` first. Capacity(C,P) =
  max(0, min(ledger remaining - historicShipped - openNet, PARTNER batch pieces of C / unit
  pieces)); historicShipped = max(0, shipped historical advance pieces - the quantity of every
  live or shipped non report-backed settlement, concepts and unshipped ones included): the ledger
  remaining already holds those settlements back and they reuse the historical pieces when they
  ship (the shipGoods rule), so counting only shipped ones took the pieces off twice until the
  settlement shipped; openNet = open sale - open buyback quantity of parts on
  (C,P); the batch term only while batches run. The rest is an own part; with batches its size is
  capped at the own batch pieces (plus pieces no batch covers), beyond that "n stuks van X zijn al
  afgerekend maar nog niet afgeboekt; Enrosed boekt die eerst af.".
- **Own cost** of an own part (`unit_cost_eur`, per sales unit, 4 dp) = external cost without the
  Enrosed kost: with batches the value of its takes (`unitCostExclEnrosedEur`; a take from a batch
  that only knows its full cost loses the Enrosed kost `ProductCostHistoryService.enrosedParts`
  explains in it; pieces without a batch cost at the card), without batches the card's
  landedCostEur minus that Enrosed part. Unknown -> 0. Pieces with no known cost beside known ones
  (no batch cost and no card) count at the average of the known ones and the part gets
  `unit_cost_estimated` = true, so the Factureren preview warns "Kostprijs deels geschat voor X";
  an own buyback copies the flag of the part it mirrors. Container parts have no unit cost.
- **Allocation of a buyback**: mirrors sale parts of the product: this melding's (the last-filled
  part first, so 100 sold with 1 back reads as 99 sold), then the partner's other open sale parts
  newest first (sold_on, melding, part), then invoiced ones; each at most its quantity minus what
  live buybacks already mirror. Each piece becomes a BUYBACK part on the mirrored part's source
  with `mirrors_line_id`; an own buyback keeps the mirrored unit cost. Except on a container
  settled through for that product (`PartnerContainerCapacity.settled`: ledger remaining -
  historicShipped <= 0, e.g. after its slotfactuur): no settlement line can come there any more,
  so the buyback goes on own stock (purchaseOrderId null, still mirroring the container part) at
  what the container's settlements charged per sales unit (settled cost / settled quantity), and
  its pieces come in as own stock at the container's cost (`StockBatchContext.partnerTakeOver`:
  an own COUNT batch "Teruggekocht na afrekening PO-..." with the container batch's cost fields,
  never into the container's batch). The own invoice credits it like any own buyback (v = -c +
  (c - k) x share, c the taken-over cost, k the terugkoopkost); a resale of that piece from own
  stock costs the same c, so together they come to (resale net - k) x share, the same money as
  netting both on the container.
  Nothing to mirror: "Meld
  eerst de verkoop op de veiling; een terugkoop hoort bij een gemelde verkoop.". Only on AUCTION.
- **Stock lines**: `StockMovement.Kind.PARTNER_SALE` ("Verkocht via partner", negative) and
  `PARTNER_BUYBACK` ("Teruggekocht door partner", positive), one per part, written by
  `StockService.partnerMove` only: product lock, never below zero at the location, reference
  "Veilingverkoop VV-n" / "Terugkoop VV-n" / "Ingetrokken: VV-n", purchaseOrderId = the part's
  container (null = own), sourceSoldAt = sold_on (StockRoll dates them by it, review when booked
  across the closing like TICA), actor = whoever is signed in. They cannot be deleted
  (`PanacheStockLedger.delete`, movement DTO `deletable` false); a product with them can neither
  be deleted nor change its stock basis. **Fix-forward**: an older build cannot read these kinds
  (`Kind.valueOf`), so once a melding exists the backend is never rolled back.
- **Batches**: a sale take is pinned to its container (own parts plain FIFO). Pieces coming back
  (a buyback, or the withdrawal of a sale) go through `StockBatchService.onPartnerReturn`: into
  the open takes of the mirrored sale part's movement and of the withdrawals of earlier buybacks
  of it (their REVERSAL rows), most recently consumed first, then those movements' unassigned
  takes. A part booked before the batch start has no takes: the start wrote what meldingen took
  out of a container into that container's BACKFILL take, so a container part (the movement's
  purchaseOrderId) re-opens its container's BACKFILL take next and its pieces stay the
  container's. Then the credit-return restore, for an own part only into own batches; never
  `onAdd` (that would make an own Telling batch). The
  batch view counts PARTNER_SALE as sold. `/api/stock-batches/check` stays empty after every
  operation. With batches off every hook is a no-op. The batch start (backfill) counts what
  meldingen took out of a partner container (`StockService.partnerReportedOut`, net of buybacks
  and withdrawals, by movement time) as having left it.
- **Withdrawal** (`withdraw`; partner `DELETE /api/partner-portal/reports/{id}` "Ongedaan maken",
  staff `DELETE /api/partner-sales/reports/{id}?reason=` "Intrekken"): only while every part is
  open; refused while a buyback of another live melding mirrors one of its sales ("Trek eerst de
  terugkoop in (VV-n)"). Checked on the net effect: per product the shelf must hold what the
  buybacks take back beyond the melding's own sales, per container the capacity must hold what its
  buybacks claim beyond its sales ("... zijn al opnieuw als verkocht gemeld; trek eerst die melding
  in"). Buyback parts are reversed first (a take pinned to their container), then sale parts (back
  into their takes); each reversal writes a REVERSAL row (mirrors_line_id = the part, its
  stock_movement_id, no money; never read as a part). A withdrawn melding stays as it is.

### Staff endpoints and the bell (stage 2)
- `PartnerSalesResource` `/api/partner-sales` (admin): `GET ?customerId=&status=OPEN|INVOICED|ALL&
  before=&limit=` (newest first, 1..200, default 100 and ALL; OPEN = OPEN + PARTLY_INVOICED,
  INVOICED = INVOICED + PARTLY_INVOICED) with `totals` {openReports, openSaleQuantity, openNetEur};
  `GET /summary` (no ledger work: the open parts in one query, `PartnerOpenParts.releasing` for the
  invoices they point at); `GET /analytics?from=&to=` (by sold_on, no cap, one row per live
  melding and one per melding x product, `open` = not fully invoiced; additive `openSaleQuantity` /
  `openNetEur` per row = only the parts still open by the open-part rule, the same figure the
  summary, home card and bell count, so a partly invoiced melding is "nog te factureren" for its
  open parts only); `GET /reports/{id}`;
  `POST /reports {customerId, ...}` (a melding staff enter for the partner: login_id null, the
  actor is the staff member); `DELETE /reports/{id}?reason=`. StaffReport = PartnerReport +
  customer, createdBy, voidedAt/By/Reason and `parts` per source (containerName null = Eigen
  voorraad, invoice id/number from the open-part rule's holder).
- `NotificationService.Kind.PARTNER_VERKOOP` from `PartnerSaleNotifications` (behind the
  `sales.application.PartnerSaleNotificationFeed` port, injected as `Instance`, failures never
  break the feed): one item per live melding of the last 14 days, actionNeeded false, **orderId
  null** (older screens would open /sales/<id>), orderNumber VV-n, title "Veiling verkocht" /
  "Direct verkocht" / "Teruggekocht op de klok", detail "100 stuks · gem. € 3,20 · netto € 277,50"
  (several products "n stuks · n producten · netto € y"), url "/veilingverkopen?melding=n",
  notificationId "partner-sale:n", additive `open` (JSON only on this kind).
- Push `PartnerSalePushNotifier` (after commit, REQUIRES_NEW, errors swallowed): kind
  "partner-sale", "Veilingverkoop gemeld", "n stuks · Veiling|Direct|Terugkoop", the url above;
  a staff entry skips that staff member's own phones. Activity `PARTNER_SALE_REPORT` CREATED /
  VOIDED.

### Factureren (stage 3)
- **Endpoints** (`PartnerSalesResource`, admin): `POST /api/partner-sales/invoice-preview
  {customerId, reportIds|null, until|null}` (null reportIds = every melding of the partner with an
  open part, sold on or before `until`) and `POST /api/partner-sales/invoice {customerId,
  reportIds}` (the preview's reportIds; parts of other meldingen are never taken). Preview:
  `groups` [{source CONTAINER|OWN, purchaseOrderId, containerName, lines [{productId, name,
  variant, soldQuantity, buybackQuantity, quantity, saleNetEur, buybackEur, proceedsEur, costEur,
  valueEur}], valueEur, advanceEur, balanceEur, finalSettlement, blocked, warnings}], `skipped`
  [{productId, name, source, purchaseOrderId, reason}], `reportIds`, `profitSharePct` (the
  customer's). Invoice: `{invoices [{id, number, source, purchaseOrderId}], blocked
  [{purchaseOrderId, message}], skipped}`. Code: `PartnerSaleInvoicing`.
- **Per source and product** over the open parts: S sold, N sale net, B bought back, K buyback
  gross; quantity q = S - B, proceeds p = N - K. q > 0: a line at max(p, 0). q = 0 (a buyback and a
  resale cancel out in pieces): its p moves onto the source's largest line (largest proceeds, then
  quantity, then lowest product id; the value is linear in the proceeds); a line with p < 0 lacks
  -p, taken from the largest lines down to 0 each. When the source's total stays below 0 the
  products that pull it down are skipped ("Terugkoopkost groter dan de netto opbrengst"); q < 0 is
  skipped ("Terugkoop groter dan verkoop: wacht op een volgende verkoop"); q = 0 without any line
  in the source is skipped ("Wacht op een volgende verkoop van dit product"). Skipped parts stay
  open. Every part of an included product (lines and absorbed ones) is linked.
- **Container source** = `SalesOrderService.createAuctionSettlementFromReports` (customer = the
  partner, the container, share / cost share / final null so the server derives them, one
  `AuctionLine(productId, q, proceeds)` per line, internal note "Veilingverkopen dd/mm/jjjj -
  dd/mm/jjjj · n meldingen"). The arithmetic of createAuctionSettlement (cost share of what
  remains, our value, advance credit, final, true-up, snapshot, extra lines) is one method,
  `planAuctionSettlement`, which returns an `AuctionSettlementPlan`; the preview reads the same
  plan through `previewAuctionSettlementFromReports` (no lock, nothing written, in a transaction
  of its own so its refusal becomes `blocked` and touches nothing else). The Factureren entry
  point uses the plain ledger remaining (its own parts are the open ones).
- **Own source** = `SalesOrderService.createPartnerSalesInvoice`: one CONCEPT FACTUUR to the
  partner, purpose STANDARD, channel PARTNER, the normal invoice series, CONTAINER_COST at markup 0
  (exact quantities, no tiers, no customer prices, no stock cost stamp), freight AANGEVULD + FIXED
  0, PALLETS without pallets, the customer's VAT. Per product c = round2(sum of sale unit_cost x
  qty - buyback unit_cost x qty), v = c + round2((p - c) x share / 100) with share =
  `Customer.partnerSharePctOrDefault()`. q > 0 and v / q > 0 at 4 dp: a line at v / q (unit cost
  c / q); otherwise (v <= 0, q = 0) an extra line "<product>: n stuks, ons deel € v" (container-cost
  pricing would replace a price of 0 by the card cost). "Afronding veilingverkopen" closes the cent
  gap: the invoice excl. VAT is exactly the sum of v. A sale part with unit cost 0 warns "Geen
  kostprijs bekend voor <product>". Notes in the settlement style (per product n stuks, netto,
  kost, resultaat, ons deel).
- **Transactions**: the invoice run is not transactional. Every source runs in
  `QuarkusTransaction.requiringNew()` with the section 3 locks (partner row, the partner's
  containers ascending, the products ascending), reads its parts again after them (still open,
  not withdrawn, of the chosen meldingen), makes the document and links the parts: `invoice_id`,
  `invoiced_at` and one append-only `partner_sale_invoice_part` row each. A refusal (e.g.
  `requireReadyForSettlement`) blocks that source only; an unexpected error too ("Dit deel kon
  niet gefactureerd worden; probeer opnieuw", logged). The created document's own activity line
  ("Deelfactuur partnercontainer X uit veilingverkopen aangemaakt" / "Factuur uit veilingverkopen
  (eigen voorraad) aangemaakt") is the activity per invoice.
- **Open again**: trashing a concept from Factureren (or a GEANNULEERD / AFGEWEZEN / VERLOPEN
  document) reopens its parts by the open-part rule; the next run links them to a new document.
- **The container's partner is fixed while meldingen are open**: `PurchaseOrderService.setPartner`
  (and so `adoptPartner`) refuses another partner or none while any open part sits on the
  container (`PartnerReportBacking.openReported`): "Er staan nog veilingverkopen op deze
  container; factureer of trek die eerst in". Factureren and its preview refuse a container source
  that is no longer the partner's before anything else (lock, adoption, default share): "Deze
  container hoort niet (meer) bij deze partner; koppel hem opnieuw of trek de meldingen in".

### Report-backed documents (stage 3)
- A document with rows in `partner_sale_invoice_part` is **report-backed**: its pieces left stock
  through the meldingen' own stock lines. Sales reads this through the port
  `sales.application.PartnerReportBacking` (implemented by `PartnerReportBackingService`, injected
  as `Instance`; absent = nothing is report-backed): `reportBacked(ids)`, `bookedQuantities(docs)`
  (per product on the lines: sale minus buyback parts, between 0 and the line quantity; one query
  for a whole list, by id up to 200 documents, else the whole aggregated table), `lastMovementAt`,
  `openReported(container)`.
- **No double stock-out, no nagging**: `issueInvoice` and `markInvoiceSent` stamp `goodsShippedAt`
  in the same transaction when every product line is covered by what its meldingen booked (time =
  the newest stock line of its parts; event and activity "Voorraad al afgeboekt via
  veilingverkopen"); no stock is booked, no stock cost stamped. `shipGoods` refuses such a document
  ("De voorraad van deze factuur is al afgeboekt via veilingverkopen"); a partly covered one (not
  possible while frozen) books only the rest. `shipGoods`' `settledShipments` (the historical
  advance rule) leaves report-backed settlements out.
- **Frozen**: a report-backed concept cannot change its lines, extras, customer, country, extra
  discount, markup mode or freight (`update`, `requireFreightEditable`), cannot become a partner
  document (`setPartnerDeal`), be copied (`duplicate`), split (`SalesSplits`), have goods returned
  on a credit note of it (`returnGoods`), or come back from the trash (`DeletedItemsService`:
  "Dit concept kwam uit Veilingverkopen. Factureer opnieuw via Veilingverkopen."; the rows survive
  the trash, so this holds even after its parts went on a new document). Settlements keep their
  own snapshot freeze and messages.
- **Minimum order value**: never for a report-backed document, in `validateInvoiceForSend` and in
  the calculator (`SalesPricingCalculator.Context.minimumExempt`, filled for an own-stock
  Veilingverkopen invoice only: an invoice, CONTAINER_COST, channel PARTNER, no partner deal, then
  the lookup), so `priced.validation.meetsMinimum` is true and `minOrderValue` 0.
- **OrderView** (single and list `GET /api/sales-orders`): additive `reportBookedQuantities`
  ({productId: qty}, null when not report-backed) and `reportBacked`.
- **Manual settlements cannot double-sell**: `createAuctionSettlement` (the sheet, not Factureren)
  limits each line to remaining - what open meldingen of the container claim ("<product>: n stuks
  staan nog open in Veilingverkopen; factureer die via Veilingverkopen") and refuses a slotfactuur
  while any is open ("Er staan nog n stuks open in Veilingverkopen; maak een deelafrekening en
  factureer de rest via Veilingverkopen (dat wordt de slotfactuur)"): the final test stays "takes
  everything the ledger has left", so the Veilingverkopen invoice of the last pieces becomes the
  slotfactuur by itself and a container never gets two. A manual slotfactuur (asked or derived) is
  also refused while any open part sits on the container, a buyback waiting for a resale included
  ("Er staat nog een terugkoop van deze container open ..."). Factureren likewise never makes a
  slotfactuur that leaves an open part of its container behind (a skipped buyback, or a melding
  outside the chosen ones): that group is blocked "Dit wordt de slotfactuur van deze container,
  maar VV-n heeft er nog een open deel; factureer dat mee of wacht op een volgende verkoop". `GET
  /api/purchase-orders/{id}/partner-settlement-availability` carries per line
  `openReportedQuantity` (sale - buyback, at least 0) and `openReportedNetEur`
  (`partnerSettlementAvailabilityWithReports`); `PartnerFinancingService.Summary` carries
  `openReportedQuantity` (the sum).
- **Year-end and the batch start**: report-backed documents are left out of
  `ClosingSeparations.invoices` (candidates and older) and of `StockCountService.documents`.
  `ClosingSeparations.partnerShipped(container, sales, cutoff, reportBacked, reportedOut)` =
  max(shipped advances, shipped non-report-backed settlements) + the net sales units the
  meldingen' stock lines of the container took out, never below 0. The closing
  (`StockClosingService.partnerLot`) counts such a line by the roll's answer for it
  (`ClosingSeparations.happenedBefore`, the same reading as `alreadyOut`) and a line the roll does
  not list by booking time before the cut-off (`ClosingSeparations.partnerReportedOut`); the batch
  start counts them by booking time before the run (`StockService.partnerReportedOut`).

### Round 2, stage 1 (2026-11-12): schema, count units, money, shapes, flags
Emre's round 2 (`design/BRIEF-R2.md`; contract `CONTRACT-R2.md`, its v2 part binding). This stage
built the backend base; edit/confirm endpoints, the buyer search and creation, the containers view,
the Factureren figures and notes come in later stages on top of it.
- **One migration** `docs/migrations/2026-11-12/partner-portal-r2-postgresql.sql` (registered after
  the round-1 file in the Dockerfile and `scripts/run-postgresql-schema-migrations.sh`), additive
  and nullable, no backfill: `partner_sale_report` + `confirmed_at/by/by_name`, `updated_at/by/
  by_name`, `revision_of` (index `idx_partner_sale_report_revision_of`), `buyer_customer_id` (index
  `idx_partner_sale_report_buyer`); `partner_sale_report_line` + `entered_unit`, `entered_count`,
  `piece_price_eur` (19,4), `entered_pieces_per_unit`; new tables `partner_sale_report_cost`
  (`cost_mode` / `cost_value`: H2 reserves VALUE), `partner_product_unit` (unique
  `uq_partner_product_unit` (customer_id, product_id)), `partner_customer_link` (unique
  `uq_partner_customer_link` (partner_customer_id, customer_id), index on customer_id),
  `partner_sale_invoice_figure` (index on sales_order_id). Entities and repositories exist for all
  (`PartnerPortalRepositories.Costs / ProductUnits / CustomerLinks / InvoiceFigures`). Checked on
  PostgreSQL 17: the prod jar with `DB_SCHEMA_STRATEGY=validate` boots on the migrated schema.
- **Archive rows** (`revision_of` set; an edit that rebooks moves the old lines and cost rows there,
  voided "Gewijzigd"): every list query filters `revisionOf is null` in SQL (partner page,
  overview, staff list, summary, analytics, bell, Factureren selection, line queries joined to the
  report). Staff read one by id (`StaffReport.revisionOf`); the partner gets 404; withdrawing one
  answers "Dit is een oude versie".
- **Count units** (`partnerportal/domain/PartnerCountUnits`, pure; the frontend mirrors it):
  STUK (the piece noun from `UnitNames` via `packaging.unitKey()`, 1 piece), VERPAKKING ("display",
  `unitPieces()`, only for a DISPLAY packaging with >= 2 pieces, either basis), OMDOOS
  (`knownPiecesPerCarton x piecesPerSalesUnit`, only when the carton content is known and > 1);
  smallest first. piecesPerSalesUnit = `unitPieces()` on a DISPLAY basis, else 1. Sales units =
  count x pieces / piecesPerSalesUnit, refused when not whole ("Een display telt 12 rozen: meld 12,
  24, 36 … rozen of tel per display."). Stock, parts, movements and settlements stay in sales units.
  Tiles (`ProductTile`, `StockTile`, `RecentSaleTile`, and every melding `Entry`) carry `salesUnit`,
  `piecesPerSalesUnit`, `pieceOne/pieceOther`, `countUnits [{unit, one, other, pieces}]` and
  `defaultUnit` (the partner's `partner_product_unit` row when the product still offers it, else
  STUK; `PartnerProductTiles.load(customerId)` / `Catalog.tile(productId, customerId)`). Staff:
  `GET /api/partner-portal-admin/{customerId}/units` -> the stored rows `[{productId, unit}]`;
  `PUT` `{changes: [{productId, unit|null}]}` partial (null or STUK removes the row; a unit the
  product does not offer is refused, the whole request then changes nothing), under the partner lock.
- **Requests**: a part is round 1 (`quantity` + `unitPriceEur` | `totalEur`) or round 2
  (`enteredUnit`, `enteredCount`, `piecePriceEur`); mixing both in one part is 400. The server
  converts once the product is locked: unit price per sales unit = round4(piece price) x
  piecesPerSalesUnit, gross = round2(quantity x unit price); entered_* are stored per side (sale
  rows and buyback rows each their own) with the unit's pieces as snapshot. The melding takes
  `extraCosts [{description (<= 80, control characters out), mode AMOUNT|PCT, value}]` (0..5;
  `extraCost` beside it is 400; a round-1 `extraCost` is one line without description) and
  `buyerCustomerId` (DIRECT only; after the partner lock: an existing customer, no partner, not
  this partner, else "Onbekende klant"; required only on an `apiVersion` 2 partner request: "Kies de
  klant of voeg er een toe"). `apiVersion` and `expectedToken` (for the edit stage) are read.
  New meldingen no longer write `extra_cost_mode/value` (round-1 rows keep theirs).
- **Money** (`PartnerSaleMoney`, identical to the frontend's to the cent): per sale entry extra =
  the PCT lines each round2(gross x pct / 100) + the entry's share of the AMOUNT lines' sum, that sum
  allocated once by gross (`PartnerSettlementLedger.allocate`). A buyback carries the melding's
  commission and transport: round2(gross x pct / 100) each, net = -(gross + commission +
  transport); `splitBuyback` allocates gross, commission and transport by quantity and derives
  each part's net the same way. **Every reader of a buyback reads its cost as -net**
  (`PartnerSaleMoney.buybackCost`): the report totals, `PartnerSaleInvoicing.ProductSum`, the
  bell, analytics, the activity summary. Round-1 buybacks have no costs, so -net = gross.
- **Views**: `Overview`, `StaffPage` and `Summary` answer `apiVersion: 2`. PartnerReport and
  StaffReport add `editToken` (= coalesce(updated_at, created_at) as ISO instant; new rows are
  written at micro precision so the token reads back equal), `updatedAt`, `updatedByName`,
  `extraCosts [{description, mode, value, amountEur}]`, `buyer {customerId, company, city}` ("Klant
  verwijderd" when gone), `confirmed` (confirmed_at set or a live document holds a part),
  `confirmedAt`, `confirmedByName`, `canEdit`. Per side of an entry `enteredUnit, enteredCount,
  piecePriceEur, enteredPiecesPerUnit, pieces` (round-1 rows, or a count that no longer matches the
  booked pieces, read in sales units: VERPAKKING on a display basis, else STUK); the buyback part
  adds `commissionEur, transportEur, costEur`. Totals: `buybackEur` = the terugkoopkost (-net), new
  `buybackGrossEur`, `salePieces`, `buybackPieces`; `netEur` = saleNet - buybackEur. The legacy
  `extraCost` is the one line, or AMOUNT of the whole extra when there are several.
  AnalyticsReport adds `buybackGrossEur`, `salePieces`, `buyer` (buybackEur = cost);
  AnalyticsProduct `salePieces` (netEur = sale net - cost); InvoicePreviewLine `buybackGrossEur`,
  `salePieces` (buybackEur = cost); StaffPart `commissionEur, transportEur, extraCostEur, pieces`.
  `GET /api/partner-portal/reports/{id}`: an own melding, 404 otherwise and for archive rows.
- **Flags per audience** (`PartnerReportReader.Loaded`; stage 2 split edit from undo): partner
  `canEdit` = OPEN, not confirmed, not an archive row; partner `canUndo` = that and no buyback of
  another live melding mirrors it; staff (StaffReport) the same two without "not confirmed".
  `Loaded.state()` = VOIDED > INVOICED > CONFIRMED > UNCONFIRMED for the list filters. The partner's withdrawal re-checks `confirmed_at` after the partner lock:
  "Enrosed heeft deze melding al bevestigd; vraag Enrosed om een wijziging.".
- **Customer delete**: refused while the customer is the buyer of a melding (voided included,
  archive rows not): "Deze klant staat op een directe verkoop (VV-n)"; otherwise its
  `partner_product_unit` rows and every `partner_customer_link` naming it go too.
- Push and bell texts count pieces ("120 rozen · gem. € 1,20 · netto ..."), averages per piece.

### Round 2, stage 2 (2026-11-12): edit, confirm, buyers and partner-made customers
- **Lock 1 on every write**: create, edit, withdraw, confirm / unconfirm (every partner involved,
  ascending), adding a customer and "Nagekeken" take `Settings.lockOrCreate` of the partner first and
  read the melding again after it (`refresh`); Factureren's `runGroup` already did. A partner's edit
  and withdrawal re-check `confirmed_at` after the lock ("Enrosed heeft deze melding al bevestigd;
  vraag Enrosed om een wijziging."). `createLocked` is split into `partnerCustomer` / `lockProducts` /
  `resolve` / `plan` / `writeParts` / `Tally`, shared by create and edit; `reverse` (the reversal
  of every part, batch-correct) is shared by withdraw and edit.
- **Bewerken** = `PUT /api/partner-portal/reports/{id}` (partner; 404 unless his own) and
  `PUT /api/partner-sales/reports/{id}` (staff; `customerId` absent or the melding's, else 400): the
  create body plus `expectedToken` (required, 400 without; the `editToken` the client loaded; another
  one is 409 "Deze melding is intussen gewijzigd; laad ze opnieuw"). Answers PartnerReport /
  StaffReport. Refused: an archive row ("Dit is een oude versie"), a withdrawn melding ("Deze melding
  is ingetrokken"), any part not open ("... al (deels) gefactureerd; wijzigen kan niet meer"), a
  changed sold-on day more than 90 days back (an unchanged day of any age is fine; `validate(request,
  fallbackSoldOn, daysBack)`; no soldOn on an edit keeps the melding's day). The server picks the path:
  - **money-only** when per product the sale and buyback quantity (sales units) are the same, the day
    is the same, and the channel changes only without a buyback: `rePrice` runs PartnerSaleMoney again
    over the same parts (their quantities in written order) and updates money and `entered_*` on the
    same line ids; no movement, no mirror or stock guard (a mirrored melding can get a price fix). An
    edit that changes nothing writes nothing (the token stays).
  - **rebook** otherwise: refused while a buyback of another live melding mirrors one of its sales
    ("Wijzigen kan niet: trek eerst de terugkoop in (VV-n); een prijs of kost wijzigen kan wel"); the
    shelf is checked on the net change per product (stock + old sale - old buyback - new sale + new
    buyback; refused only when the edit itself takes it below zero); every part is reversed as a
    withdrawal does (reference "Gewijzigd: VV-n", a buyback reversal may dip, the net was checked),
    the old SALE / BUYBACK / REVERSAL rows move to the **archive row**, then the new parts are planned
    and booked on the same melding at `locationOf(report)` (never the current settings; `locationId`
    stays). A container whose claim grows (an old buyback there went elsewhere) needs that room as it
    was before the edit ("... zijn al opnieuw als verkocht gemeld; trek eerst die melding in"). The
    plan's own refusals read "Wijzigen kan niet: er liggen nog maar ...".
  - Both: the commission / transport snapshot stays unless the channel changes (then the settings on
    AUCTION, 0 on DIRECT); note, buyer and cost lines are replaced; `extra_cost_mode/value` are
    cleared (cost lines live in `partner_sale_report_cost`); `updated_at/by/by_name` (micro precision,
    always after the previous token); a staff edit keeps `confirmed_at`. Activity `UPDATED` "Bedragen
    gewijzigd: 110 verkocht · netto € 203,50 → € 212,50" or "Gewijzigd: ..."; push "Veilingverkoop
    gewijzigd" (`PartnerSalePushNotifier.Ready.title`).
  - **Archive row** (rebook, or a money-only edit whose cost lines changed): copies customer, login,
    channel, day, percents, the round-1 extra cost, note, location, created_*, confirmed_*, updated_*,
    buyer; `client_ref` null, `revision_of` = the melding, voided now "Gewijzigd"; the old cost rows are
    re-pointed to it. Never listed, staff read it by id.
- **Bevestigen / Terugzetten** (`PartnerSaleConfirmations`): `POST /api/partner-sales/reports/confirm`
  and `/unconfirm` with `{reports: [{id, token}]}` (legacy `{reportIds}` without a token check) ->
  `{reports: [StaffReport]}`. One transaction, all or nothing, at most 500: unknown id, archive row,
  withdrawn melding and a token that differs ("VV-n is intussen gewijzigd; bekijk ze opnieuw") are
  refused; unconfirm also once a document holds a part ("VV-n staat al op F-...; terugzetten kan niet
  meer"). A melding already in the asked state is left alone. Activity `CONFIRMED` "Bevestigd" /
  `UNCONFIRMED`. Factureren does not yet look at confirmation (a later stage).
- **Bell**: PARTNER_VERKOOP feeds meldingen made or edited in the last 14 days
  (`Reports.liveTouchedSince`), `at` = the later of created / updated, notificationId
  `partner-sale:<id>` until edited, then `partner-sale:<id>:<editToken>` (each edit is a new item, the
  dismissed state of never-edited items survives the deploy), detail ends in " · gewijzigd". New kind
  **PARTNER_KLANT** (same port): one per customer a partner added that staff have not reviewed,
  actionNeeded true, orderId and orderNumber null, customer = its company, title "Nieuwe klant via het
  partnerportaal", detail "Aangemaakt door Frans (Verhoeven) · vul de gegevens aan", url
  `/customers?klant=<id>`, notificationId `partner-customer:<id>`, at = when it was added.
- **Buyer** (`buyer(...)` after lock 1): DIRECT only, an existing customer, no partner, not the
  partner itself; a partner request may also only name a customer its search would show (below),
  except the buyer the melding already has; one text "Onbekende klant" for all of these.
- **Customers for the partner** (`PartnerCustomerService`, native SQL in `PartnerCustomerQueries`,
  only id / company / city / countryCode read): `GET /api/partner-portal/customers?q=` -> `[{customerId,
  company, city, countryCode, addedByYou}]`: q needs 2 letters or digits (else `[]`, not counted),
  at most 100 characters, `\`, `%`, `_` taken literally (escaped, `escape '\'`), company or city
  contains it, names starting with it first, at most 20; partners and the caller never; left out: a
  customer whose every sales document (trashed ones too) is a website request still waiting for review
  (CONCEPT, OFFERTE, internal note starting with `[WEBSITE_AANVRAAG]`), unless this partner added it.
  No q = the buyers of his last live meldingen, then the customers he added, at most 8. 300 searches
  per login per day (`PublicFormRateLimiter.checkKey(PARTNER_CUSTOMER_SEARCH, "LOGIN", ...)`, fixed UTC
  day), then 429 "Je zocht vandaag al heel vaak naar klanten; probeer het morgen opnieuw." with
  Retry-After (`PartnerRefusals.tooMany`). `POST /api/partner-portal/customers {company, countryCode?,
  city?, email?, phone?}`: control characters out; company required (<= 160, spaces collapsed), city
  <= 100, email <= 160 and a@b.c, phone <= 40, countryCode in `Locale.getISOCountries()` (default the
  partner's own country, else NL); under lock 1: the same normalized name (accents, case, punctuation
  and spaces aside) among the customers this partner added answers that one (200), at most 20 new per
  partner per Brussels day ("Vandaag kwamen er al 20 nieuwe klanten bij; ..."), then
  `CustomerService.createMinimal` (no VAT rule, NIEUW, NL, notes "Aangemaakt via Partnerportaal door
  Frans op dd/mm/jjjj", activity "Klant aangemaakt via Partnerportaal (Frans)") and a
  `partner_customer_link` (added_by_partner, login, created_*) -> 201. Picking an existing customer
  writes no link.
- **Staff side of partner-made customers**: `/api/customers` (list, get, create and update answers)
  now answers `CustomerView` = the Customer record unwrapped plus `partnerOrigin {partnerName,
  createdByName, createdAt, reviewedAt}` only for a customer a partner added (port
  `sales.application.CustomerPartnerOrigins`, implemented by `PartnerCustomerOriginReader`; the request
  body stays `Customer`, unknown fields are ignored). `CustomerService.update` skips the VAT rule while
  the stored record has no VAT number and a partner-made link is unreviewed. `POST
  /api/partner-portal-admin/customers/{customerId}/reviewed` ("Nagekeken") stamps reviewed_at/by on its
  partner-made links under the partners' locks and answers the origin; 404 for a customer no partner
  added; activity on CUSTOMER "Nagekeken (aangemaakt via Partnerportaal)". Deletion (stage 1 listener):
  a buyer of a non-archive melding stays; otherwise its links go.

## Track & trace: what the field reads, moved arrivals, failures (2026-11-10)

Emre: "Bekijk ook of de track trace beter kan." Production has no ShipsGo token: automatic data comes
from the Evergreen date lookup (Bexy) and from Voydar after a ship is linked by hand. Everything here
works without ShipsGo and keeps working with it. Details: `docs/integrations/container-tracking.md`
(section 2026-11-10).

- **One rule reads the reference**: `ContainerTracking.recognise(raw)` → `Recognised(kind, number,
  checkDigitOk, container, evergreenBl)`, kind EMPTY / CONTAINER / BL / BOOKING / MULTIPLE / LINK /
  UNKNOWN. The frontend runs the same rule (`features/purchasing/tracking-reference.ts`): change both
  or neither. `reference(raw)` asks `strictReference` (the parser of 2026-10-26, untouched) first, so
  every key that existed stays; reminder identities use `strictReference` only. `recognise` reports what
  `strictReference` follows when it takes the text, so the line names the followed number (the
  frontend mirrors the strict parser for this). A bare Evergreen bill
  (`EGLV` + 12 digits) now counts and its dates are accepted (`TrackingDateSync.sameShipment`, a
  superset of the old acceptance). No other bare number is guessed.
- **Rows** (`ContainerTracking.Row`) end with `recognised, failure, failureAt, failureDataAt, arrivalMovedFrom,
  arrivalMovedAt, arrivalMovedDays, manualCheckAt, refreshOutcome`. Everything the native iOS app
  and older screens read (`status`, `statusLabel`, `error`, `source`, `automatic`, `checkedAt`,
  `updatedAt`, `nextCheckAt`, `orderExpectedArrival`, …) is computed as before.
  `GET /api/container-tracking?orderId=` answers one order's row.
- **Failure** = last attempt failed (`TrackingStore.Snapshot.checkFailed`: error and `checkedAt` ≠
  `updatedAt`), also without a source; `failureDataAt` is the failing snapshot's own last success.
  Not a failure: a date that could not be applied (`checkDates` writes it with the same instant as
  its success: keep that), and `BexyClient.NO_DATA_YET` while the bill never gave data (once it did,
  it is the failure "De datumbron gaf bij de laatste controle geen gegevens."). A received order
  sends no failure.
- **Manual check**: `refreshOutcome` CHECKED / COOLDOWN / BUSY / OFF / NO_SOURCE; `manualCheckAt` is
  when a check does something again (`MANUAL_SECONDS_PAID` 60, `MANUAL_SECONDS_FREE` 900).
  The service re-reads the order through `TrackingDateSync.reread` (its own transaction) after a
  check: a plain `orders.findById` in the same request answers the order as first read, so the
  answer named the old date right after a new one was written. Keep that re-read.
- **Moved arrival**: `purchase_order.arrival_moved_from / arrival_moved_at / arrival_notice_date /
  arrival_notice_at`
  (entity-only columns, the `PurchaseOrder` record and the order JSON do not carry them; migration
  `docs/migrations/2026-11-10/purchase-order-arrival-moved-postgresql.sql` with its Dockerfile COPY
  and runner line). Written only by `TrackingDateSync.noteArrivalMove`; `PanacheSourcingRepositories
  .save` clears them when the saved date differs (a date typed by hand is the planning from then
  on). `ArrivalMoveStore` reads them and builds the bell row ("Aankomst 15/11/2026 · 3 dagen later",
  detail "was 12/11/2026": always the order's date now, shown while two days or more from the
  planning; kind `CONTAINER_AANKOMST`, no push; a new identity only when the date lies two days or
  more from the last named one; its time is `arrival_notice_at`, so a one-day correction neither
  re-shows a dismissed row nor moves it to the top).
- **Ship link**: `VesselLinkStore.rebind` (called in `PurchaseOrderService.update`) keeps the link
  when the reference text is rewritten, unless both texts are recognised with no number in common.
  The order's port-call snapshot moves with it (`ContainerTrackingService.vesselKey` holds the
  reference hash).
- **Time**: "today" in `shipmentPlan` is the Brussels day; the Voydar window starts the day before
  `shippedOn`.
- **How it was checked** (no test files; scripts live outside the repo): recognition, the row
  fields, `?orderId=`, the rebind and the hand-edit clearing went through the API with tracking
  off. The outcomes BUSY / COOLDOWN / CHECKED / NO_SOURCE, a failed check and the bell row went
  through the API with tracking on and every outward call refused locally (a JVM proxy on a closed
  port). The write side (a check that moves the arrival: 5 → 8 → 9 → 10 → 5 december, the bell row
  following by the two-day rule) ran against a local stand-in for the date source that only the
  test JVM trusted. Never run against the real Bexy, Voydar or ShipsGo, nor on PostgreSQL.
