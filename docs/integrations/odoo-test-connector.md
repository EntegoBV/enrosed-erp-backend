# Odoo 19 connector — production reads and test outbound

This connector uses an Odoo 19 JSON-2 API and imports bank movements into ENROSED. ENROSED remains responsible for local payment allocation. Bank/document reads are separated from an explicitly gated outbound invoice workflow. No method reconciles or modifies bank data in Odoo. There is no scheduler: connection checks, bank imports, draft creation and Peppol test submission require explicit administrator actions.

## Server configuration

Configure values separately for each backend environment, using its deployment secret store. Production uses the read-only API configuration below; outbound experiments belong to a separate Odoo test database. Never put API keys in browser configuration, source control or screenshots.

| Environment variable | Required | Meaning |
| --- | --- | --- |
| `ENROSED_ODOO_ENABLED` | Yes to enable | `true`; default `false` |
| `ENROSED_ODOO_BASE_URL` | Yes | HTTPS origin, e.g. `https://your-test-database.odoo.com`; no path/query/userinfo |
| `ENROSED_ODOO_DATABASE` | Yes | Exact Odoo database name |
| `ENROSED_ODOO_API_KEY` | Yes | Secret API key for a dedicated integration user |
| `ENROSED_ODOO_COMPANY_ID` | Yes | Numeric Odoo company ID; deliberately no automatic first-company selection |
| `ENROSED_ODOO_JOURNAL_IDS` | No | Comma-separated numeric bank journal IDs; omitted means every accessible bank journal in the company |
| `ENROSED_ODOO_OUTBOUND_ENABLED` | For outbound drafts | `true`; default `false` |
| `ENROSED_ODOO_SEND_ENABLED` | For explicit post/send | `true`; default `false`; does not bypass the remote Peppol test-mode check |
| `ENROSED_ODOO_SALES_JOURNAL_ID` | For outbound | Explicit sales journal in the selected company |
| `ENROSED_ODOO_INCOME_ACCOUNT_ID` | For outbound | Explicit income account in the selected company |
| `ENROSED_ODOO_VAT_TAX_IDS` | For outbound | JSON mapping from the exact ENROSED `VatTreatment` name to its configured Odoo sale-tax ID, for example `{"BINNENLAND":123}`. IDs are installation-specific; do not copy the example ID. Exempt treatments each need their own deliberate mapping. |

These map to `enrosed.odoo.enabled`, `.base-url`, `.database`, `.api-key`, `.company-id`, `.journal-ids`. No external network request is made when disabled or incomplete. Odoo Online requires a plan permitting external API access, and JSON-2 requires Odoo 19. API-key permissions and company access remain enforced by Odoo. Minimum readable models: `res.company`, `res.currency`, `account.journal`, `account.bank.statement.line`, `account.move`, `ir.attachment`. Configure accounting, the bank provider, and Peppol in the intended Odoo environment separately; the connector does not enroll a company in Peppol or configure KBC.

## Production read-only API configuration

Production was configured on 2026-10-13. The authenticated ENROSED connection check confirmed the Odoo company and bank journal. The first bank import created all 74 accessible posted nonzero rows; a repeat created zero and reported all 74 unchanged. The latest available source transaction was dated 2026-10-08, so this verifies the data available in Odoo rather than the freshness of the upstream bank connection. The live document view listed 25 incoming and 8 outgoing documents with their available attachments and Odoo statuses. Live Peppol submission remains disabled.

Use the production backend service's Railway variables for the five required settings: `ENROSED_ODOO_ENABLED`, `ENROSED_ODOO_BASE_URL`, `ENROSED_ODOO_DATABASE`, `ENROSED_ODOO_API_KEY` and `ENROSED_ODOO_COMPANY_ID`. The verified non-secret values are base URL `https://enrosed.odoo.com`, database `enrosed` and company ID `1`; `res.company` identifies that company as `ENROSED`, VAT `BE1034273386`. These identifiers were confirmed through the API, not inferred from the URL or an assumed first company. A dedicated integration user should have only the company and accounting read access needed above. Keep the connector disabled until all settings are ready.

Pass the key to `railway variable set ENROSED_ODOO_API_KEY --stdin --skip-deploys`, with explicit production project, environment and backend-service arguments. Supply the value through standard input, never a `KEY=value` command argument; suppress secret-bearing command output. Stage the other settings with `--skip-deploys` as well, then deploy once to activate the complete configuration. Verify through authenticated `/api/odoo/status` and the explicit read-only `/api/odoo/check` action before requesting a bank import. Inspect the returned counts and document results to confirm what the configured Odoo account can actually read.

Production deliberately leaves `ENROSED_ODOO_OUTBOUND_ENABLED` and `ENROSED_ODOO_SEND_ENABLED` unset, so both remain `false`. No outbound journal, income-account or tax mapping is needed for reads. Imports write bank rows only in ENROSED; the connector does not change or reconcile Odoo bank data. The existing outbound implementation remains restricted to Peppol test mode and cannot send a live production Peppol invoice.

`.railway/railway.ts` preserves the five production variable names without storing their values. It neither creates credentials nor copies them to test. If optional journal scope or other Odoo variables are later configured, add their names to the corresponding environment's preserve list before the next `railway config apply`. The test backend stays disabled until separately configured with its own Odoo test database and key; never reuse the production key there.

## Behavior and limitations

- Bank sync reads all accessible **posted, nonzero** statement lines in the company/journal scope, including lines already reconciled in Odoo. Draft/cancelled lines are excluded because they do not represent booked bank money. Zero-amount rows are also excluded: they contain no money movement and cannot become local bank transactions. It does not filter on `is_reconciled`, date or invoice status. Bank/credit journals include archived journals (`active_test:false`). A maximum ID captured at the start bounds the run; new lines above it are picked up next run. IDs paginate in ascending order. Every later run rereads older lines so changed references/statuses are considered.
- Only amounts in EUR journal currency are imported. Non-EUR bank lines are counted and skipped, never relabelled or converted to EUR. A foreign-currency purchase paid from an EUR journal imports the EUR journal `amount`, not `amount_currency`.
- An Odoo statement date has no bank timestamp. It is represented as midnight in `Europe/Brussels`; this is a date conversion, not an asserted payment time. `write_date` is interpreted as UTC.
- The source identity is SHA-256 of the Odoo hostname/database/company plus the statement-line ID. A retry has the same identity. Each imported row has its own short local transaction; network requests run outside transactions. A persistent renewable lease prevents simultaneous imports across backend instances.
- No local allocation is created, removed or changed by a sync. Source reconciliation is informational only. The banking import service retains financial values on linked local rows and reports conflicts when the source changed them. A previously imported source line later cancelled/deleted is not removed locally; deletion/cancellation detection is not implemented, so this is not an authoritative complete mirror. Previously entered manual bank lines are not automatically deduplicated against imported statements and need explicit local review.
- Account metadata comes from `/api/bank-accounts`. An account with imported Odoo provenance is protected immediately: manual bank movements are refused for its canonical key and explicit aliases. A sync registers the actual EUR journal/account binding and preserves existing labels, aliases and archive state. A connection check makes no such local write. The binding fails closed on a changed provider namespace, journal or account; synthetic fallback account names are never treated as verified bank account numbers. The official ledger for an Odoo account uses its imported movements; historical manual entries and unlinked payments remain available for review and explicit matching.
- Account removal is local, recoverable archiving. It hides an account from active overview/options without deleting balances, statement rows, payments or allocations, and does not stop imports or close the account at the bank. Aliases are explicit and only group historical names; they do not rewrite payment/account history. A managed canonical name or another imported Odoo account cannot be silently absorbed as an alias.
- A partial failure returns `complete:false`, a controlled code and counts of already committed rows. Retrying is safe. Configuration/read failures never return a fake successful sync. The stored last run is an operational result, not proof of current provider completeness.
- The document view reads purchase/sales invoices and credit notes from Odoo without creating local invoices. `state` is the Odoo accounting state; `peppolState` is the unmodified optional `peppol_move_state`. Odoo 19 values: `ready`, `to_send`, `skipped`, `processing`, `done`, `error`. `done` should be shown as “Odoo: voltooid”, and a missing state as unknown. A purchase invoice in Odoo is not necessarily received through Peppol.
- Downloaded PDF/XML files are the bytes stored on the invoice's Odoo attachments. File type alone does not prove an original received Peppol payload. Embedded binary-field attachments are included. Downloads verify that the invoice is in the configured company and the attachment belongs to that invoice, refuse URL attachments, and are limited to 15 MiB. No remote attachment URL is followed.
- Outbound invoice creation/submission uses a separate durable outbox and both flags default to false. The configured company must have an active Peppol proxy user with `edi_mode:test`. A production/demo proxy or missing registration blocks outbound preparation and submission. These routes cannot submit a live production Peppol invoice.

## API

All routes require the ERP administrator role.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/odoo/status` | Configuration/last-check/last-sync state; never credentials |
| POST | `/api/odoo/check` | Read-only company, bank journal/schema and bank-read check |
| POST | `/api/odoo/bank-sync` | Explicit paginated import; counts, `complete`, `code`, Dutch message |
| GET | `/api/bank-accounts` | Derived and saved accounts, including archived entries, explicit aliases and source authority |
| PUT | `/api/bank-accounts/{key}` | `{label,source,odooJournalId,aliases}` saves metadata; a new Odoo mapping verifies the actual provider account |
| DELETE | `/api/bank-accounts/{key}` | Archive locally; preserves financial history |
| POST | `/api/bank-accounts/{key}/restore` | Restore the local overview entry |
| GET | `/api/odoo/documents?direction=incoming&afterId=0&limit=50` | Invoice/credit note page; `outgoing` selects sales; newest IDs first |
| GET | `/api/odoo/documents/{id}/attachments/{attachmentId}` | Bound stored attachment, download disposition, no-store |
| GET | `/api/odoo/outbox/{salesOrderId}` | Local preview (also when unconfigured), frozen outbox entry and configuration status |
| GET | `/api/odoo/outbox/{salesOrderId}/partners` | Named matching customer candidates, without creating/changing customers |
| POST | `/api/odoo/outbox/{salesOrderId}/preview` | `{odooPartnerId}` freezes the checked invoice/customer/company and tax/journal mappings |
| POST | `/api/odoo/outbox/{salesOrderId}/draft` | `{snapshotHash}` explicitly creates one Odoo draft |
| POST | `/api/odoo/outbox/{salesOrderId}/send` | `{snapshotHash}` explicitly posts and submits the checked draft using only Peppol |
| POST | `/api/odoo/outbox/{salesOrderId}/refresh` | Reads/reconciles uncertain remote creation/submission; never resends |

Document pagination returns `nextAfterId` and `hasMore`. Pass the previous `nextAfterId` to fetch older IDs. Connection status may be ready while Peppol is not installed: the document result reports `peppolSupported:false`. It does not claim Peppol registration or bank-provider freshness.

## Validation checklist

After configuring a new environment, check the bank journals, compare the imported count with the accessible Odoo statement-line count for the selected snapshot and journal scope, and verify available purchase and sales XML/PDF attachments. Exercise retries and exceptional cases against a dedicated Odoo test database: one reconciled and one unreconciled KBC line; multiple pages; duplicate sync; an EUR account paying a foreign invoice; a non-EUR journal; edits to an allocated line; a missing Peppol module; denied company/attachment permissions. Never alter production bank data to manufacture these test cases.

The enabled configuration has a real CDI injection and HTTP status regression test: immutable configuration beans use singleton scope because their fields are read directly. Live Odoo saas 19.3 exposes journal IBANs as `bank_account_number` and attachment contents as `raw` (a base64 JSON string). The connector discovers these fields and retains the older `bank_acc_number` and `datas` fallbacks.

## Outbound controls and remaining verification

The outbox accepts ordinary issued sales invoices and credit notes with the standard `FULL` payment plan (including its implicit default). Advance/final settlement invoices, partner financing/settlement, fiscal-representative invoices, structured instalment plans and split documents are refused until their specific semantics are mapped. Credit notes require one matching posted original invoice in Odoo. Odoo partners are selected by business name; the server requires matching VAT number, name, address, postcode, city and country. It does not guess missing customer mappings or create partners.

The preview freezes the current ENROSED priced document and identities before any remote mutation. Source changes or mapping changes invalidate the next action. The outbox preserves the official number, invoice/due dates, descriptions, quantities, unit prices, line discounts, order-discount lines, shipping, tax mapping and credit reference. Before posting and again before sending, remote totals **and every product line** must match exactly. A rounding/sequence/tax difference blocks sending rather than being silently adjusted. The configured Odoo tax must be a sale percentage tax excluding VAT with the exact rate; tax treatment mapping is explicit, never derived merely from a zero rate.

The unique local invoice entry has a SHA-256 snapshot and stable remote `ref` marker. A lease serializes each invoice workflow. `UNKNOWN_CREATE` is committed before `account.move.create`; `UNKNOWN_SEND` is committed before posting/submission. If a call times out or the backend restarts, no automatic retry is permitted. Refresh can adopt exactly one matching draft or report the remote queued/done status; unknown send outcomes stay uncertain until positive remote evidence exists. Source/database changes fail closed.

Posting uses public `account.move.action_post`. Peppol submission uses public `account.move.send.wizard.create` with only `sending_methods:["peppol"]`, followed by `action_send_and_print` with PDF fallback disabled. No email fallback is enabled and no private Odoo method is called. `processing`/`to_send` are queued states, not delivery confirmation. `done` is shown as the upstream Odoo status. These paths are unit tested against controlled responses, **not yet verified with a real Odoo test database**. Exact Odoo subscription, permissions, journal sequence, tax setup and Peppol test registration remain required integration checks.

References: [Odoo JSON-2 documentation](https://www.odoo.com/documentation/19.0/developer/reference/external_api.html), [Odoo 19 statement line source](https://github.com/odoo/odoo/blob/19.0/addons/account/models/account_bank_statement_line.py), [Odoo 19 Peppol invoice states](https://github.com/odoo/odoo/blob/19.0/addons/account_peppol/models/account_move.py).

## Incoming invoice registration from a bank movement

An admin can explicitly select a received Odoo bill (`in_invoice`, outgoing bank movement)
or supplier credit (`in_refund`, incoming bank movement). Both `draft` and `posted` provider
states are accepted; cancelled documents and non-EUR documents are refused. Odoo drafts
received via Peppol are source invoices, not ENROSED-issued documents. No remote document
is posted, changed or paid by this workflow.

- `GET /api/bank-statements/{id}/odoo-purchase-invoices?afterId=0&limit=50` returns the
  existing flat Odoo document fields plus `companyCostId`, `registered`, `eligible`,
  `unavailableReason`. Pagination is the same descending Odoo-ID cursor as the document list.
- `GET /api/bank-statements/{id}/odoo-purchase-invoices/{documentId}/preview` refreshes the
  source financial facts. Optional `existingCompanyCostId` validates an explicitly selected
  candidate. It returns `document`, `snapshotHash`, signed `invoiceTotalEur`, `netEur`,
  `vatEur`, `paymentAmountEur`, `differenceEur`, `supplierCountry`, `companyCostId`,
  `existingCandidates`, `eligible`, `unavailableReason`, and `actionMessage`.
- `POST /api/bank-statements/{id}/odoo-purchase-invoice` requires
  `{odooInvoiceId,snapshotHash,existingCompanyCostId?,category?}`. The reply contains
  `{line,companyCostId,alreadyRegistered,invoiceTotalEur,paymentAmountEur,differenceEur,documentsSaved}`.
- `GET /api/costs/{id}/odoo-source` returns null or the immutable source snapshot,
  saved attachment-to-media IDs and registration time. Original draft/posted state,
  Peppol evidence, supplier identity and exact source net/VAT/gross remain available.

A unique source namespace/company/move identity prevents duplicate registration and is
retained even if the local cost is later deleted. Existing-cost candidates require explicit
selection and financial validation; monthly invoices are not merged by amount alone.
A provider refresh before registration rejects a changed preview. Downloads occur before
one DB transaction creates/adopts the cost, reuses/creates its payment, links the selected
bank movement, saves PDF/XML media and records provenance. An invalid document rolls all
financial writes back. XML is stored as a downloadable binary document, never executed.
Same-bank retries return the original local IDs without another payment. Exclusive historical
manual bank links may move to the selected real bank movement while preserving payment IDs
and the historical row. An existing imported-bank link is never silently moved.

Gross means `account.move.amount_total`, not the residual/payable balance. Mixed VAT totals
are preserved exactly, without an invented blended percentage; VAT classification and
eligibility for deduction remain unconfirmed until reviewed. Supplier credits create negative
costs and negative cost payments. Original source amounts, date, supplier and reference cannot
be edited through the ordinary local cost form.

Bank matching allows at most EUR 0.02 between the **signed bank amount and the complete
signed allocation**, with consistent cash direction for every component. Original amounts
are never rounded into agreement. DTO `allocatedAmountEur` and `allocationDifferenceEur`
show the signed allocation and `bank - allocation` difference. A new payment closes the
original remaining cost within this tolerance; existing payment amounts remain unchanged.


## Ordinary local costs and invoices

Bank allocation is not restricted to Odoo source documents. Existing endpoints also cover
ordinary local documents: `/bank-statements/{id}/allocation` for sales,
`/cost-payment` for a new local cost payment, `/links` for existing cost/purchase payments,
and `/purchase-payment` for explicit purchase payment parts. The last endpoint requires
original currency/amount and the actual EUR amount; a purchase reconciliation budget must
not be presented as a separately registered supplier invoice or converted into fictional FX.
`/purchase-share` shares one outgoing movement over containers in one transaction
(`{parts:[{paymentId,amountEur,expectedAmountEur?} | {purchaseOrderId,amountEur,payee,instalmentDue,label}]}`):
an open payment is linked and follows its part (grows, or is carved with the rest left open),
a container without one gets a new EUR payment; the parts make the movement within EUR 0.02
and an identical retry returns the same rows.

The local cost-payment body is `{companyCostId,confirmNewPayment?:boolean}`. The optional
flag defaults to false. A bank amount above the remaining cost plus EUR 0.02 is always
refused. A matching existing payment which is unlinked or only on a historical manual bank
movement requires reuse, or an explicit `confirmNewPayment:true` decision for a genuinely
separate equal instalment. The flag does not bypass amount/direction/ownership checks.
Earlier instalments already linked to official imported movements do not cause ambiguity.
Partial payments remain supported; same-bank retries return the original payment/link.

For a new ordinary local expense from an outgoing movement, use
`POST /api/bank-statements/{id}/cost-registration` with
`{requestId:"<canonical UUID>",cost:{...new CompanyCost fields...}}`. The admin-only
endpoint locks the bank movement and creates the validated cost, one payment and its
link in one transaction. The returned `{line,cost}` contains the normal enriched, flat
cost view (including `id`, `payments`, `cashTotalEur` and `openEur`). The original invoice
date and amounts remain; the payment uses the bank date and account. The cost gross must
match the absolute outgoing movement within EUR 0.02. Use the existing-cost payment
endpoint for partial payments. Client `paidOn` is ignored so it cannot create a second
payment automatically.

Keep the same request ID and exact submitted cost after an uncertain response. A durable
`bank_cost_registration` receipt returns the original IDs for retries and refuses another
registration on that bank movement, including after a local cost is deleted. It intentionally
has no cascading financial foreign keys. A changed body, changed bank facts or removed link
requires checking the existing registration; retry never silently recreates or rewrites it.
The request ID is transport metadata and should not appear in product copy. Upload documents
through the existing cost attachment API after the registration returns; retain the known
cost ID and pending files if upload fails.

Definite validation failures return HTTP 422 (malformed JSON: 400). HTTP 409 returns a
`code`: `BANK_COST_REGISTRATION_EXISTS` for a different request ID on this bank,
`BANK_COST_REGISTRATION_CHANGED` for a changed/replaced earlier registration, or
`BANK_ALREADY_ALLOCATED` when another flow already linked the movement. Network errors
and server failures have an unknown outcome: preserve the original request and retry.
Migration `2026-10-13/bank-cost-registration-postgresql.sql` runs after the source-import
migration and is safe to run again.
