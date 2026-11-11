# Container tracking (test rollout, 2026-10-26)

The ERP page `/purchasing/tracking` lists non-archived purchase orders with a tracking reference. It reads persisted backend observations rather than issuing carrier requests from the browser. The server checks eligible references every six hours and records the last successful data, attempted check, next check and meaningful change separately. Errors must leave the last successful observation visible with its age.

## Accountless ship updates (Voydar)

The optional vessel association uses the official [Voydar Atom feeds](https://www.voydar.com/developers/feeds), which require neither an account nor an API key. [Voydar terms, section 13](https://www.voydar.com/terms) allow these feeds in apps with a link back to each entry. The ERP retains each displayed event's source attribution and Voydar source URL. It fetches only `https://www.voydar.com/feeds/vessel/{IMO}`; the IMO must be seven digits with a valid checksum. No arbitrary URL is fetched, and no coordinates are scraped from an iframe.

Staff link or remove a known ship with `PUT /api/container-tracking/{orderId}/vessel` and `{ "imo": "9811000", "trackingReference": "<current reference>" }` (IMO here is an example, never a default). A null IMO removes the association. The optional reference acts as a stale-form guard: a changed reference returns 409. The saved association belongs to that order and its current tracking reference; changing the reference does not inherit the ship. This never rewrites the original tracking reference. A ship should be taken from that container's actual bill of lading or confirmed carrier information, never inferred from a similar reference.

The backend checks linked ships every six hours, including when ShipsGo is not configured. A durable shared cache enforces at least fifteen minutes between attempts for the same IMO across orders and manual refreshes, matching Voydar's minimum interval. HTTP 304 and an empty Atom feed are valid responses; earlier known port-call events are retained. Network failures retain the last successful observation. XML parsing disables DTDs and external entities; responses are capped at 1 MB with a 25-second complete-body timeout. Feed-generation timestamps do not count as shipment changes.

Only arrival/departure events are displayed, excluding name, ownership and sanctions changes. The voyage window begins the day before the order's known departure day (`shippedOn` minus one day, UTC midnight; `shippedOn` is the origin port's own day, so UTC midnight of that day cut off the departure that set it); without that date it starts at the time the ship was linked. Historical port calls before the voyage are omitted. Voydar's public feed provides ship port calls, **not proof that the container remains on that ship**, container milestones, a verified container ETA or machine-readable coordinates. The source remains labelled `VOYDAR`, status `VESSEL_TRACKING` (Schip gekoppeld); ETA and position fields remain null. A separately attributed official Voydar embed may display the ship map without pretending it is a container observation.

The feed response has `configured: true` while accountless tracking is enabled, plus `shipsGoConfigured` for the optional paid integration. Setting `CONTAINER_TRACKING_ENABLED=false` disables external checks for both providers; stored observations and vessel associations remain viewable. Each row reports `source`, `vesselImo` and `automatic`; an unlinked row is not automatically tracked merely because the feature is available.

Without a source-specific stored HTTPS link, the external carrier lookup opens the accountless [track-trace container directory](https://www.track-trace.com/container). This is a manual lookup link, not a background data API.

## Accountless Evergreen date lookup

An explicitly recorded Evergreen master B/L (`EGLV` plus twelve digits) can also use the documented public [bexy MCP](https://bexy.io/mcp) endpoint, without an account or key. The server performs the MCP initialize/initialized/`track-shipment` sequence against the fixed HTTPS endpoint. It checks the echoed B/L and selected ISO container number, requires a unique container selection, and accepts only a `carrier_eta` explicitly ending at `port_of_discharge`. Container owner prefixes alone do not select a carrier.

This is a lookup of bexy's cached carrier record, not a request to refresh Evergreen. `data_as_of` is retained as `dateSourceCheckedAt`; an HTTP response received now cannot conceal that the source is older than 24 hours. Expired or mismatched records retain the saved purchase dates and show the rejection reason. The per-order/per-shipment date snapshot has its own persisted six-hour schedule; manual checks have a fifteen-minute cooldown, and the global tracking disable flag also disables these requests. Failures keep the last successful date observation. Bexy loaded/onboard status is never treated as proof of actual ship departure.

## Optional full container provider and activation

The connector uses the official [ShipsGo v2 API](https://api.shipsgo.com/docs/v2/) with `X-Shipsgo-User-Token`. Configure `SHIPSGO_API_TOKEN` on the **test** Railway backend service to activate. Keep this value in server configuration; never put it in frontend variables, source, screenshots or logs. The current rollout does not create a ShipsGo account or buy a subscription.

The [ShipsGo trial](https://help.shipsgo.com/article/108-is-there-a-sandbox-environment-for-testing) offers three shipment credits usable for API tests; there is no separate provider sandbox. Requests with a valid key concern real shipments. Continued use requires shipment credits. The [API pricing page](https://help.shipsgo.com/article/100-api-usage-pricing), updated 21 October 2026, says the API fee is waived below 100 shipments per year; fees for other volumes depend on the plan. Confirm the account's conditions before enabling paid use. Provider updates may be less frequent than ERP polling: checking every six hours does not guarantee a new carrier observation every six hours.

Supported inputs are a container number (four letters and seven digits), an explicit `BL: <master B/L>` or `BOOKING: <carrier booking number>`, and supported links from which a number can be extracted unambiguously. A forwarder's internal reference or house B/L may not be trackable. Existing references are preserved and uncertain references ask for clarification. No arbitrary URL in a reference is fetched by the backend.

Shipment creation uses a stable reference and reuses the provider's duplicate response. The integration does not populate email followers. A stable purchase-order identity and normalized tracking reference identify the registration. Retries for the same order reuse it; a later purchase using the same physical container cannot inherit an old voyage. Separate purchase orders may consume separate provider credits.

## Location meaning

ShipsGo GeoJSON returns route segments and ports. A `CURRENT` LineString's `properties.current.coordinates` is the vessel position; a `CURRENT` Point represents a port. Coordinates are `[longitude, latitude]`. The ERP labels these as a vessel position or last reported location respectively. The provider does not supply a coordinate observation time in this payload; the ERP's fetch time must never be presented as a GPS observation time. Routes or planned ports must not be interpolated into a supposed current position.

The map uses OpenStreetMap with attribution and validated coordinates. If the provider is unconfigured, has no position, or fails, the UI explains this rather than showing a fabricated position.

## Rollout boundary and verification

### Transport dates saved on the purchase

Tracking rows include `orderExpectedArrival` and `orderShippedOn`, read from the canonical purchase again after a refresh. `dateSource`, `dateSourceCheckedAt` and `dateError` describe the independent date observation and any reason it could not be applied. A successful ERP request does not make an old provider cache fresh: automatic date changes require a source check within 24 hours and no more than five minutes in the future.

The date synchronizer requires the exact shipment identity, both operational route ports and a current voyage anchor. Existing departure dates permit at most three days of source correction; without one, the source voyage must begin after the purchase date. Source voyages older than 180 days, impossible arrival windows, mismatched linked ships and unrecognised routes preserve the saved dates. Null or blank legacy port fields use the existing purchase domain defaults (Ningbo / Rotterdam), while the locked write still checks that the recorded route fields have not changed.

Only actual departure evidence at the configured **origin** can update `shippedOn`. ShipsGo's actual origin `DEPA` is usable; loaded/onboard evidence can anchor an ETA to the voyage but cannot invent a departure. Voydar can supply an actual origin departure only with a recognised port timezone and an unambiguous voyage; a transit stop such as Singapore on a Ningbo–Rotterdam shipment cannot replace the original shipping date. Without an existing departure date, a Voydar departure must occur after the ship was associated with the purchase. Voydar itself has no machine-readable ETA.

Database writes lock and recheck the exact reference, recorded route, order date, status, notes and existing transport dates after the network request. Concurrent manual changes win. Received, archived, deleted and concept purchases are not changed. Only `shippedOn` and `expectedArrival` are written, with an idempotent audit entry; this code never invokes the purchase lifecycle, marks an order received, books stock or creates a payment. Arrival reminders read the resulting canonical `expectedArrival` independently.

Publish backend and frontend only to their existing remote `test` branches. Confirm deployment completion separately from Git publication and verify the test URLs on phone and desktop. Production (`main`) is outside this rollout.

Local map QA may use clearly named fictitious fixtures on localhost. Such fixtures must never be added to the live database or presented as real tracking. Without a ShipsGo token, full carrier/container observations remain unverified. Voydar can be verified independently for an explicitly linked real ship; passing connector tests does not prove that a real six-hour scheduled interval has elapsed.

## 2026-11-10: what the field reads, moved arrivals, failures and manual checks

Nothing here needs ShipsGo; everything keeps working with it.

### What the field reads

`ContainerTracking.recognise(raw)` and the frontend's `features/purchasing/tracking-reference.ts` hold the same rule; change both or neither. Thirty-three written references were compared on both sides through the API when it was built. The text is first normalised: every kind of space (also non-breaking, as pasted from a mail) becomes a plain space, zero-width characters are dropped, the ends are trimmed, then upper case. Rules, in order:

1. Empty → `EMPTY`. Longer than 500 characters → `UNKNOWN`.
2. Starts with `BL:` or `BOOKING:` (the form of 2026-10-26): the number is the whole rest when it matches `[A-Z0-9/-]{6,60}`, else a leading Evergreen bill (`EGLV` + 12 digits, a space allowed), else the first word when it matches and holds a digit. A container number behind it is reported as `container`; more than one → `MULTIPLE`. No number but an Evergreen bill somewhere in the text → `BL` with that bill. Else `UNKNOWN`.
3. Container numbers anywhere in the text (`4 letters + 7 digits`, the check digit may stand apart: `TCNU 123456 5`): exactly one → `CONTAINER` with `checkDigitOk` (ISO 6346, category U/J/Z); several different ones → `MULTIPLE`. A wrong check digit warns, it does not block.
4. `B/L:`, `BOEKING:` + number (a digit is required), or `B/L`, `BL`, `BOOKING`, `BOEKING` + space + number (at least 8 characters with a letter and a digit, so `BL 10/11/2026` is not a bill) → `BL` / `BOOKING`.
5. Exactly one Evergreen bill in the text → `BL`. No other bare number is guessed.
6. `http(s)://…` without a number → `LINK` (kept for the "Trackingwebsite openen" button, not followed). Else `UNKNOWN`.

Every result carries `evergreenBl`: the bill `BexyClient.query` itself reads from the text as typed, so the screen says "datums via Evergreen" exactly when the date lookup runs.

`ContainerTracking.reference(raw)` asks the parser of 2026-10-26 first (`strictReference`, unchanged): a text it accepts keeps its key, so no snapshot, ShipsGo registration, ship link or reminder identity moves. Only texts it rejects are read by the wider rule. Newly followed: a bare Evergreen bill, `B/L:` forms, a number followed by other text behind `BL:`, the split check digit. `TrackingDateSync.sameShipment` accepts everything it accepted before, first of all the Evergreen bill the lookup read from the text (a bare `EGLV…` got lookups but its dates were refused before).

A rewritten reference keeps the linked ship (`VesselLinkStore.rebind`, called in the order save) unless both texts are recognised and have no number in common.

### Row fields added (null when they do not apply)

`recognised {kind, number, checkDigitOk, container, evergreenBl}`; `failure`, `failureAt`; `arrivalMovedFrom`, `arrivalMovedAt`, `arrivalMovedDays`; `manualCheckAt`; `refreshOutcome` (only on the answer of refresh and vessel). `status`, `statusLabel`, `error`, `source`, `automatic`, `checkedAt`, `updatedAt`, `nextCheckAt` and `dateError` are computed as before; the native iOS app reads those.

`GET /api/container-tracking?orderId=20` answers the same feed object with that order's row, or no row when the order is unknown, archived or has no reference. A purchase order page reads this every minute instead of the whole feed.

### A failed check

`failure` is set when the last attempt failed: the snapshot has an error and `checkedAt` differs from `updatedAt` (`Snapshot.checkFailed`). It is also set when no source answered yet, where `error` holds guidance text. Two things are not failures and stay the `dateError` line: a date that could not be applied (written right after a success with the same instant in `checkDates`; keep that) and the date lookup's "nog geen actuele opgeslagen gegevens" (`BexyClient.NO_DATA_YET`), the usual answer for a fresh bill.

### A manual check says what it did

`refreshOutcome`: `CHECKED` a source was asked; `COOLDOWN` the last attempt is too recent (60 s ShipsGo, 900 s Voydar and the date lookup) and `manualCheckAt` says from when a check does something again; `BUSY` the scheduled run holds the lease; `OFF` tracking is switched off; `NO_SOURCE` nothing is linked that could be asked.

### The arrival tracking moved

`purchase_order.arrival_moved_from`, `arrival_moved_at`, `arrival_notice_date` (nullable; migration `docs/migrations/2026-11-10`). `TrackingDateSync.noteArrivalMove` is the only writer:

- The date that stood before tracking first replaced it stays "was" through later moves, so "3 dagen later" is always against the planning that stood. Back on that date clears the move. A first date (the order had none) is not a move.
- **A date saved through the order is the planning from then on**: `PanacheSourcingRepositories.save` clears the three columns when the saved date differs from the stored one. Tracking may still replace a hand-typed date at its next valid check, as before; that then shows as a move from the typed date. A hand date that wins until the source changes is a possible later decision, not built.
- A received order shows no move.
- The bell (`ArrivalMoveStore.notice`, through `ContainerArrivalReminders.feed`): one `CONTAINER_AANKOMST` row "Aankomst 3 dagen later" / "12/11/2026 → 15/11/2026", no push. `arrival_notice_date` is the date the row names; tracking names a new date only when it lies two days or more from the last named one (from the planning date the first time). The row leaves the feed 14 days after the last move, at receipt, when archived, or when the date is saved by hand. It can stand beside the 10/3-day reminder of the same container.

### Time

"Today" in `TrackingDateSync.shipmentPlan` is the Brussels day (was UTC). The Voydar voyage window starts the day before `shippedOn`. Not changed: ShipsGo timestamps without an offset are read as UTC; `portZone` knows six countries, so a Voydar departure from another origin never sets the departure date; a carrier's departure day is taken in the carrier's own offset (three days of tolerance exist).

## Alternative researched

[SafeCube](https://www.safecube.ai/product/pricing) advertises a small-volume plan including API access and a [trial](https://www.safecube.ai/developers/developers). It would require its own connector and account. This implementation uses ShipsGo's documented schema; it does not imply that ShipsGo is the cheapest provider for the user's volume.
