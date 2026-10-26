# Container tracking (test rollout, 2026-10-26)

The ERP page `/purchasing/tracking` lists non-archived purchase orders with a tracking reference. It reads persisted backend observations rather than issuing carrier requests from the browser. The server checks eligible references every six hours and records the last successful data, attempted check, next check and meaningful change separately. Errors must leave the last successful observation visible with its age.

## Accountless ship updates (Voydar)

The optional vessel association uses the official [Voydar Atom feeds](https://www.voydar.com/developers/feeds), which require neither an account nor an API key. [Voydar terms, section 13](https://www.voydar.com/terms) allow these feeds in apps with a link back to each entry. The ERP retains each displayed event's source attribution and Voydar source URL. It fetches only `https://www.voydar.com/feeds/vessel/{IMO}`; the IMO must be seven digits with a valid checksum. No arbitrary URL is fetched, and no coordinates are scraped from an iframe.

Staff link or remove a known ship with `PUT /api/container-tracking/{orderId}/vessel` and `{ "imo": "9811000", "trackingReference": "<current reference>" }` (IMO here is an example, never a default). A null IMO removes the association. The optional reference acts as a stale-form guard: a changed reference returns 409. The saved association belongs to that order and its current tracking reference; changing the reference does not inherit the ship. This never rewrites the original tracking reference. A ship should be taken from that container's actual bill of lading or confirmed carrier information, never inferred from a similar reference.

The backend checks linked ships every six hours, including when ShipsGo is not configured. A durable shared cache enforces at least fifteen minutes between attempts for the same IMO across orders and manual refreshes, matching Voydar's minimum interval. HTTP 304 and an empty Atom feed are valid responses; earlier known port-call events are retained. Network failures retain the last successful observation. XML parsing disables DTDs and external entities; responses are capped at 1 MB with a 25-second complete-body timeout. Feed-generation timestamps do not count as shipment changes.

Only arrival/departure events are displayed, excluding name, ownership and sanctions changes. The voyage window begins at the order's known departure day (`shippedOn`, UTC midnight); without that date it starts at the time the ship was linked. Historical port calls before the voyage are omitted. Voydar's public feed provides ship port calls, **not proof that the container remains on that ship**, container milestones, a verified container ETA or machine-readable coordinates. The source remains labelled `VOYDAR`, status `VESSEL_TRACKING` (Schip gekoppeld); ETA and position fields remain null. A separately attributed official Voydar embed may display the ship map without pretending it is a container observation.

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

## Alternative researched

[SafeCube](https://www.safecube.ai/product/pricing) advertises a small-volume plan including API access and a [trial](https://www.safecube.ai/developers/developers). It would require its own connector and account. This implementation uses ShipsGo's documented schema; it does not imply that ShipsGo is the cheapest provider for the user's volume.
