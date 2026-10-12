# Odoo 19 connector — test rollout

This connector uses an Odoo 19 JSON-2 API and imports bank movements into ENROSED. ENROSED remains responsible for local payment allocation. Bank/document reads are separated from an explicitly gated outbound invoice workflow. No method reconciles or modifies bank data in Odoo. There is no scheduler: connection checks, bank imports, draft creation and Peppol test submission require explicit administrator actions.

## Server configuration

Configure these values only on the **test backend**, using the deployment secret store. Never put API keys in browser configuration, source control or screenshots.

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

These map to `enrosed.odoo.enabled`, `.base-url`, `.database`, `.api-key`, `.company-id`, `.journal-ids`. No external network request is made when disabled or incomplete. Odoo Online requires a plan permitting external API access, and JSON-2 requires Odoo 19. API-key permissions and company access remain enforced by Odoo. Minimum readable models: `res.company`, `res.currency`, `account.journal`, `account.bank.statement.line`, `account.move`, `ir.attachment`. Install/configure accounting, the bank provider, and Peppol separately in the Odoo test environment; the connector does not enroll a company in Peppol or configure KBC.

## Behavior and limitations

- Bank sync reads all accessible **posted, nonzero** statement lines in the company/journal scope, including lines already reconciled in Odoo. Draft/cancelled lines are excluded because they do not represent booked bank money. Zero-amount rows are also excluded: they contain no money movement and cannot become local bank transactions. It does not filter on `is_reconciled`, date or invoice status. Bank/credit journals include archived journals (`active_test:false`). A maximum ID captured at the start bounds the run; new lines above it are picked up next run. IDs paginate in ascending order. Every later run rereads older lines so changed references/statuses are considered.
- Only amounts in EUR journal currency are imported. Non-EUR bank lines are counted and skipped, never relabelled or converted to EUR. A foreign-currency purchase paid from an EUR journal imports the EUR journal `amount`, not `amount_currency`.
- An Odoo statement date has no bank timestamp. It is represented as midnight in `Europe/Brussels`; this is a date conversion, not an asserted payment time. `write_date` is interpreted as UTC.
- The source identity is SHA-256 of the Odoo hostname/database/company plus the statement-line ID. A retry has the same identity. Each imported row has its own short local transaction; network requests run outside transactions. A persistent renewable lease prevents simultaneous imports across backend instances.
- No local allocation is created, removed or changed by a sync. Source reconciliation is informational only. The banking import service retains financial values on linked local rows and reports conflicts when the source changed them. A previously imported source line later cancelled/deleted is not removed locally; deletion/cancellation detection is not implemented, so this is not an authoritative complete mirror. Previously entered manual bank lines are not automatically deduplicated against imported statements and need explicit local review.
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
| GET | `/api/odoo/documents?direction=incoming&afterId=0&limit=50` | Invoice/credit note page; `outgoing` selects sales; newest IDs first |
| GET | `/api/odoo/documents/{id}/attachments/{attachmentId}` | Bound stored attachment, download disposition, no-store |
| GET | `/api/odoo/outbox/{salesOrderId}` | Local preview (also when unconfigured), frozen outbox entry and configuration status |
| GET | `/api/odoo/outbox/{salesOrderId}/partners` | Named matching customer candidates, without creating/changing customers |
| POST | `/api/odoo/outbox/{salesOrderId}/preview` | `{odooPartnerId}` freezes the checked invoice/customer/company and tax/journal mappings |
| POST | `/api/odoo/outbox/{salesOrderId}/draft` | `{snapshotHash}` explicitly creates one Odoo draft |
| POST | `/api/odoo/outbox/{salesOrderId}/send` | `{snapshotHash}` explicitly posts and submits the checked draft using only Peppol |
| POST | `/api/odoo/outbox/{salesOrderId}/refresh` | Reads/reconciles uncertain remote creation/submission; never resends |

Document pagination returns `nextAfterId` and `hasMore`. Pass the previous `nextAfterId` to fetch older IDs. Connection status may be ready while Peppol is not installed: the document result reports `peppolSupported:false`. It does not claim Peppol registration or bank-provider freshness.

## Required validation before production consideration

No real connection has been verified without test credentials. Test with a dedicated Odoo database: one reconciled and one unreconciled KBC line; multiple pages; duplicate sync; an EUR account paying a foreign invoice; a non-EUR journal; edits to an allocated line; purchase and sales XML/PDF attachments; a missing Peppol module; denied company/attachment permissions. Compare the imported count with the accessible Odoo statement-line count for the selected snapshot and journal scope.

## Outbound controls and remaining verification

The outbox accepts ordinary issued sales invoices and credit notes with the standard `FULL` payment plan (including its implicit default). Advance/final settlement invoices, partner financing/settlement, fiscal-representative invoices, structured instalment plans and split documents are refused until their specific semantics are mapped. Credit notes require one matching posted original invoice in Odoo. Odoo partners are selected by business name; the server requires matching VAT number, name, address, postcode, city and country. It does not guess missing customer mappings or create partners.

The preview freezes the current ENROSED priced document and identities before any remote mutation. Source changes or mapping changes invalidate the next action. The outbox preserves the official number, invoice/due dates, descriptions, quantities, unit prices, line discounts, order-discount lines, shipping, tax mapping and credit reference. Before posting and again before sending, remote totals **and every product line** must match exactly. A rounding/sequence/tax difference blocks sending rather than being silently adjusted. The configured Odoo tax must be a sale percentage tax excluding VAT with the exact rate; tax treatment mapping is explicit, never derived merely from a zero rate.

The unique local invoice entry has a SHA-256 snapshot and stable remote `ref` marker. A lease serializes each invoice workflow. `UNKNOWN_CREATE` is committed before `account.move.create`; `UNKNOWN_SEND` is committed before posting/submission. If a call times out or the backend restarts, no automatic retry is permitted. Refresh can adopt exactly one matching draft or report the remote queued/done status; unknown send outcomes stay uncertain until positive remote evidence exists. Source/database changes fail closed.

Posting uses public `account.move.action_post`. Peppol submission uses public `account.move.send.wizard.create` with only `sending_methods:["peppol"]`, followed by `action_send_and_print` with PDF fallback disabled. No email fallback is enabled and no private Odoo method is called. `processing`/`to_send` are queued states, not delivery confirmation. `done` is shown as the upstream Odoo status. These paths are unit tested against controlled responses, **not yet verified with a real Odoo test database**. Exact Odoo subscription, permissions, journal sequence, tax setup and Peppol test registration remain required integration checks.

References: [Odoo JSON-2 documentation](https://www.odoo.com/documentation/19.0/developer/reference/external_api.html), [Odoo 19 statement line source](https://github.com/odoo/odoo/blob/19.0/addons/account/models/account_bank_statement_line.py), [Odoo 19 Peppol invoice states](https://github.com/odoo/odoo/blob/19.0/addons/account_peppol/models/account_move.py).
