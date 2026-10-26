# Container tracking (test rollout, 2026-10-26)

The ERP page `/purchasing/tracking` lists non-archived purchase orders with a tracking reference. It reads persisted backend observations rather than issuing carrier requests from the browser. The server checks eligible references every six hours and records the last successful data, attempted check, next check and meaningful change separately. Errors must leave the last successful observation visible with its age.

## Provider and activation

The connector uses the official [ShipsGo v2 API](https://api.shipsgo.com/docs/v2/) with `X-Shipsgo-User-Token`. Configure `SHIPSGO_API_TOKEN` on the **test** Railway backend service to activate. Keep this value in server configuration; never put it in frontend variables, source, screenshots or logs. The current rollout does not create a ShipsGo account or buy a subscription.

The [ShipsGo trial](https://help.shipsgo.com/article/108-is-there-a-sandbox-environment-for-testing) offers three shipment credits usable for API tests; there is no separate provider sandbox. Requests with a valid key concern real shipments. Continued use requires shipment credits and a [separate quoted API fee](https://help.shipsgo.com/article/100-api-usage-pricing). Confirm the account's conditions before enabling paid use. Provider updates may be less frequent than ERP polling: checking every six hours does not guarantee a new carrier observation every six hours.

Supported inputs are a container number (four letters and seven digits), an explicit `BL: <master B/L>` or `BOOKING: <carrier booking number>`, and supported links from which a number can be extracted unambiguously. A forwarder's internal reference or house B/L may not be trackable. Existing references are preserved and uncertain references ask for clarification. No arbitrary URL in a reference is fetched by the backend.

Shipment creation uses a stable reference and reuses the provider's duplicate response. The integration does not populate email followers. A stable purchase-order identity and normalized tracking reference identify the registration. Retries for the same order reuse it; a later purchase using the same physical container cannot inherit an old voyage. Separate purchase orders may consume separate provider credits.

## Location meaning

ShipsGo GeoJSON returns route segments and ports. A `CURRENT` LineString's `properties.current.coordinates` is the vessel position; a `CURRENT` Point represents a port. Coordinates are `[longitude, latitude]`. The ERP labels these as a vessel position or last reported location respectively. The provider does not supply a coordinate observation time in this payload; the ERP's fetch time must never be presented as a GPS observation time. Routes or planned ports must not be interpolated into a supposed current position.

The map uses OpenStreetMap with attribution and validated coordinates. If the provider is unconfigured, has no position, or fails, the UI explains this rather than showing a fabricated position.

## Rollout boundary and verification

Publish backend and frontend only to their existing remote `test` branches. Confirm deployment completion separately from Git publication and verify the test URLs on phone and desktop. Production (`main`) is outside this rollout.

Local map QA may use clearly named fictitious fixtures on localhost. Such fixtures must never be added to the live database or presented as real tracking. Without a provider token, end-to-end carrier observations and the passage of a real six-hour interval remain unverified even when connector tests and UI checks pass.

## Alternative researched

[SafeCube](https://www.safecube.ai/product/pricing) advertises a small-volume plan including API access and a [trial](https://www.safecube.ai/developers/developers). It would require its own connector and account. This implementation uses ShipsGo's documented schema; it does not imply that ShipsGo is the cheapest provider for the user's volume.
