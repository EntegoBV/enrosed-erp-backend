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
- Contact: hello@enrosed.com · retail webshop: enrosed.com
- Frontend repo: `enrosed-erp-frontend` (Angular 22, Vercel)
- This repo deploys to Railway via the `Dockerfile` (Java 25 multi-stage)

## Standing conventions (agreed with the owner)

- **Code and code comments in English.** UI texts and user-facing strings
  are Dutch (the owner works in Dutch). Commit messages in English.
- Commits are grouped per topic ("aparte commits" per feature batch).
- **Translations never live in code.** They are CSV resources under
  `src/main/resources/i18n/` (document-text.csv, colour-names.csv,
  payment-terms.csv), 8 languages: NL FR EN DE ES PL PT TR. A parity test
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
   adapter/in     REST resources (Basic auth: enrosedadmin, RolesAllowed)
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

### Catalog / products
- Product: SKU, name, colour (translated via dictionary), sizes, carton
  (pieces per carton drives all rounding), barcodes (piece EAN-13 + outer
  ITF-14), HS code, EXW price + currency, landed cost + source, markup or
  fixed sales price, stock, photos (stored IN the database as blobs -
  survives Railway redeploys; port `PhotoStorage` allows an S3 later).
- CSV master-data export/import, and a separate translations CSV
  (8 languages) the owner edits in Excel.
- Catalogue PDF: language choice, chapters per category with
  descriptions, two full photos per product card.

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
