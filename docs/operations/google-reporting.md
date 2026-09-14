# Google reports in Analyses > Website

The ERP's own website statistics, Google Analytics and Google Search Console are
separate measurement sources. Do not add their visitors or conversions together.
The Google integration reads reports; it does not submit visits or leads.

Authenticated staff use `GET /api/analytics/website/google?days=30`. The response
contains independent `googleAnalytics`, `searchConsole` and `realtime` sources.
Each source carries its own status, range, retrieval time and optional data.
The requested range is bounded to 1–365 days. No migration changes existing
website visit rows.

Standard reports are cached on the server for 15 minutes; realtime is cached
for one minute. A temporary failure may show a clearly marked cached standard
report for up to 24 hours, or realtime data for up to five minutes. These are
retrieval caches, not a background polling job. The frontend's refresh control
respects the server cache to avoid exhausting Google API quotas.

## Server configuration

Configure these variables on the ERP backend, never on the public website or in
the Angular build:

| Variable | Value |
| --- | --- |
| `GOOGLE_ANALYTICS_PROPERTY_ID` | `554014865` |
| `GOOGLE_SEARCH_CONSOLE_SITE_URL` | `sc-domain:enrosed.com` |
| `GOOGLE_REPORTING_SERVICE_ACCOUNT_JSON` | Optional service account JSON credential, stored as a deployment secret; leave empty when using internal OAuth |
| `GOOGLE_REPORTING_USER_CREDENTIALS_JSON` | Internal OAuth authorized-user envelope, stored as a deployment secret; leave empty when using a service account |

The website measurement ID (`G-SZPBRC1X6J`) identifies the collection tag. It is
not the reporting property ID or an API credential. The test website continues
to exclude Google Analytics collection even when an ERP test backend is allowed
to read production reports.

## Grant access

1. In Google Cloud, use a project managed by ENROSED/Entego and enable the
   **Google Analytics Data API** and **Google Search Console API**.
2. Create a dedicated reporting service account. This task requires neither a
   broad project IAM role nor Google Workspace domain-wide delegation.
3. In GA4, add its email address to property **ENROSED · enrosed.com** as
   **Viewer**.
4. In Search Console, add the same email to the **enrosed.com domain property**
   with **Restricted** access for reading search-performance reports.
5. Store the JSON credential only in the backend secret variable. Do not commit
   it, put it in a public asset, paste it into logs, or include it in a support
   export. Redeploy the backend after configuration changes.

Google Cloud IAM membership alone does not grant access to the Analytics or
Search Console property. Reporting uses only these OAuth scopes:

- `https://www.googleapis.com/auth/analytics.readonly`
- `https://www.googleapis.com/auth/webmasters.readonly`

For key rotation, configure the replacement credential, verify both report
sources, and then revoke the old key in Google Cloud. Removing a property grant
or disabling an API should produce a visible connection error, not zero-valued
traffic statistics.

## Internal OAuth when organization policy blocks service account keys

Keep the organization policy intact. The reporting backend also supports an
internal OAuth app in the managed Google organization. Use one credential mode
only: configuring both credential variables is rejected rather than silently
choosing an identity.

1. Enable the same two reporting APIs in the managed Google Cloud project.
2. Create an **Internal** OAuth app and client for the authorized reporting user
   (`emre@entego.be`). Grant only `analytics.readonly` and
   `webmasters.readonly` using the full scope URLs listed above. Request offline
   access so the backend can refresh access without repeated browser sign-in.
3. Confirm the user has access to the GA4 property and Search Console site.
4. Store `GOOGLE_REPORTING_USER_CREDENTIALS_JSON` as a backend deployment secret
   with exactly the fields `type` (value `authorized_user`), `client_id`,
   `client_secret`, and `refresh_token`. Leave
   `GOOGLE_REPORTING_SERVICE_ACCOUNT_JSON` empty. Never place the real envelope
   in this repository, a frontend build, logs or a support export.
5. Redeploy and verify both providers. OAuth revocation or expiry must appear as
   a visible connection error. Obtain fresh consent and replace the secret when
   needed; the ERP never asks for a Google password.

The backend uses Google's `UserCredentials` refresh flow through the same fixed
Google token endpoint, bounded network transport and shared failure cooldown.
It has no generic credential-file/ADC loader or configurable OAuth redirect/token
endpoint, and it does not request additional scopes when refreshing a token.

## Interpret reports

The GA4 property started collecting on **14 September 2026**. Earlier ERP
statistics remain available under the ERP's own measurement source; they cannot
be reconstructed as historical GA4 visitors. Analytics only receives website
events after the visitor allows analytics cookies.

Realtime and standard Google reports have different processing schedules. A new
property may show realtime visits while its standard reports are still empty.
Search Console reports final search data using Google's Pacific-time reporting
dates. Use the source's displayed reporting range and retrieval time when
comparing figures.

`generate_lead` records a successfully submitted quote or contact request.
Opening a quote page in the ERP's own visit report is interest, not a submitted
lead. Search Console query and page tables contain top results and may omit
anonymized queries; use the separately requested overall totals rather than
summing those tables.

## Verification

- Confirm the Google reporting endpoint is inaccessible without ERP staff
  authentication.
- Open each source in **Analyses > Website**. Check its connection state,
  reporting dates and retrieval time before interpreting totals.
- Verify at least one real API response from both Google services after grants
  are configured. Empty valid reports and permission failures are distinct.
- Do not submit fake website enquiries or inject synthetic leads to test the
  reporting integration.

References: [Analytics Data API](https://developers.google.com/analytics/devguides/reporting/data/v1/rest/v1beta/properties/runReport),
[Search Analytics query](https://developers.google.com/webmaster-tools/v1/searchanalytics/query),
[Google Analytics data freshness](https://support.google.com/analytics/answer/11198161),
[Search Console permissions](https://support.google.com/webmasters/answer/7687615).
