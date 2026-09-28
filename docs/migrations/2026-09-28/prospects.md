# Private prospect CRM

All `/api/prospects` routes require the existing ERP administrator role. The module stores business contact information and outreach history; it never sends or queues email and has no public endpoints.

Run `prospects-postgresql.sql` through the deployment migration runner. It adds three tables and indexes without changing existing customer, supplier, order, mail or catalogue tables. Local/test H2 uses the ORM schema strategy. The singleton lock is safely seeded on startup for H2 and PostgreSQL.

## API

* `GET /api/prospects?search=&status=&countryCode=&page=0&size=50`: `{items,total,page,size}`. Maximum size 200. Search matches business name, email, Instagram handle and group key; `%` and `_` are treated literally.
* `GET /api/prospects/{id}`: `{prospect,activities}`; activities newest first.
* `POST /api/prospects`: create, HTTP201, flat prospect response.
* `PUT /api/prospects/{id}`: full field update, flat prospect response. Business name/country are required; omitted optional fields clear them, except omitted status retains its current value.
* `POST /api/prospects/{id}/activities`: log an activity, HTTP200. No message is sent.
* `GET /api/prospects/email-summary?date=2026-09-28`: `{date,timezone,limit,sent,reserved,scheduled,remaining,activities}`. Default date is today in Europe/Brussels. Uses the next local midnight boundary, including DST changes.
* `POST /api/prospects/{id}/email-reservations`: reserve a daily email slot before an external/manual send.

Prospect input: `businessName`, `countryCode` (ISO3166 alpha2, GB for United Kingdom), `language` (e.g. EN, en-GB), `email`, `website`, `instagramHandle`, `groupKey`, `sourceUrl`, `sourceType`, `status`, `notes`. Email and Instagram handle are normalized to lower case and unique when present. Instagram accepts a handle, @handle or an absolute instagram.com profile URL. URLs must be absolute HTTP(S). `groupKey` is an explicit lower-case cross-branch identity; the API never guesses corporate relationships from names.

Prospect statuses: `NEW`, `QUALIFIED`, `CONTACTED`, `INTERESTED`, `NOT_INTERESTED`, `CUSTOMER`, `DO_NOT_CONTACT`.

Activity input: `channel`, `type`, `status`, `subject`, `body`, `occurredAt`, `externalId`, `attachmentName`, `sourceUrl`. Channel: `EMAIL`, `INSTAGRAM`, `PHONE`, `NOTE`. Type is an uppercase identifier up to 64 characters, such as `OUTREACH`, `INTRODUCTION`, `FOLLOW_UP`, `REPLY`, `LIKE`, `COMMENT`, `PROFILE_REVIEW`, `NOTE`. Status: `DRAFT`, `SCHEDULED`, `SENT`, `FAILED`, `RECEIVED`, `COMPLETED`, `CANCELLED`; `RESERVED` can only be created using the reservation route; `SCHEDULED` requires an existing reservation. An ISO8601 instant is accepted for `occurredAt`; omitted means now, allowing five minutes of clock skew. RESERVED/SCHEDULED use the reserved future send time; other activity history cannot be in the future. Actor and created timestamps are server supplied. Activity responses also include read-only `recipientEmail` and `groupKey` snapshots so subsequent prospect edits do not remove email deduplication evidence. Attachment fields are descriptive metadata, not an attachment upload.

Activities are append-only, except reservation scheduling/completion. A global unique `externalId` makes retries idempotent. Reusing it with a different prospect or payload returns 409. A missing external ID permits separate manual entries. Reservation completion may omit unchanged subject/body/attachment/source fields. A retry with omitted `occurredAt` preserves the original time. Read errors are 404; invalid input 400; duplicate/conflicting records 409.

## Ten-email reservation workflow

1. POST `/{id}/email-reservations` with `{externalId,subject,body,attachmentName?,sourceUrl?,scheduledFor?}`. Use a stable unique ID per attempted message, e.g. `gmail-draft:<draftId>`. Optional `scheduledFor` is a future ISO8601 instant; omission reserves now. Response is an `EMAIL` / `OUTREACH` / `RESERVED` activity whose occurredAt is the intended send time.
2. Only after a successful reservation, send or schedule through the approved external email workflow. This endpoint has not sent anything. Use the returned recipientEmail snapshot for the external message; a reservation retry after editing the prospect email/group is rejected. After verifying a native Gmail scheduled message, record `{channel:"EMAIL",type:"OUTREACH",status:"SCHEDULED",externalId}`; the reserved time is preserved. A conflicting timestamp is rejected. SCHEDULED does not mean SENT.
3. POST `/{id}/activities` with `{channel:"EMAIL",type:"OUTREACH",status:"SENT",externalId}` once the actual external send is verified. This changes occurredAt to the actual send instant (now by default), including for an earlier SCHEDULED message. Record `FAILED` or `CANCELLED` to release an unused slot. An uncertain external send must be checked before any retry.

A database row lock serializes reservations and idempotency across application replicas. At most ten SENT+RESERVED+SCHEDULED records can reserve slots for the intended Brussels calendar day. Future-day capacity is separate from today. Reservations reject a prospect without email or with DO_NOT_CONTACT/NOT_INTERESTED/CUSTOMER status; an excluded branch also suppresses its explicit group. A previous SENT or pending RESERVED/SCHEDULED email to the same prospect, snapshotted recipient or group blocks another introductory reservation. Reservations do not expire automatically: close failed/cancelled attempts explicitly, and never send using an old-day reservation. A closed or earlier-day reservation cannot be reused. Reusing a closed/old reservation ID returns 409. No follow-up email automation is implemented.

Manual historical SENT logging intentionally does not enforce the ten-email limit or suppression policy: an already-sent message must remain recordable truthfully. Its counts still reduce remaining daily reservation slots. The limit controls only workflows that reserve **before** sending; it cannot prevent email sent independently of this API. Reserve against the intended send day, mark native scheduling only after verification, and record SENT only after actual delivery workflow evidence. External rescheduling requires cancelling the old reservation and obtaining a new one; an externally sent message is always recorded on its actual date. Logs and summaries are private admin data.

## Focused verification

`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dtest=ProspectResourceTest test`

Tests cover authorization, persistence/filtering, normalized duplicate detection, activity idempotency/conflicts, Brussels midnight and DST, recipient/group deduplication, reservation capacity/release, future scheduled quotas/transitions and concurrent requests.
