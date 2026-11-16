# Container tracking (test rollout, 2026-10-26)

The ERP page `/purchasing/tracking` lists non-archived purchase orders with a tracking reference. It reads persisted backend observations rather than issuing carrier requests from the browser. The server checks eligible references every six hours and records the last successful data, attempted check, next check and meaningful change separately. Errors must leave the last successful observation visible with its age.

## Accountless ship updates (Voydar)

The optional vessel association uses the official [Voydar Atom feeds](https://www.voydar.com/developers/feeds), which require neither an account nor an API key. [Voydar terms, section 13](https://www.voydar.com/terms) allow these feeds in apps with a link back to each entry. The ERP retains each displayed event's source attribution and Voydar source URL. It fetches only `https://www.voydar.com/feeds/vessel/{IMO}`; the IMO must be seven digits with a valid checksum. No arbitrary URL is fetched, and no coordinates are scraped from an iframe.

Staff link or remove a known ship with `PUT /api/container-tracking/{orderId}/vessel` and `{ "imo": "9811000", "trackingReference": "<current reference>" }` (IMO here is an example, never a default). A null IMO removes the association. The optional reference acts as a stale-form guard: a changed reference returns 409. The saved association belongs to that order and its current tracking reference. A rewritten reference keeps the ship unless the text now names another shipment (`VesselLinkStore.rebind`; sections 2026-11-10 and 2026-11-16 below). Linking a ship never rewrites the tracking reference. A ship should be taken from that container's actual bill of lading (since 2026-11-16 the ERP reads it from the dossier, section 2026-11-16 below) or confirmed carrier information, never inferred from a similar reference.

The backend checks linked ships every six hours, including when ShipsGo is not configured. A durable shared cache enforces at least fifteen minutes between attempts for the same IMO across orders and manual refreshes, matching Voydar's minimum interval. HTTP 304 and an empty Atom feed are valid responses; earlier known port-call events are retained. Network failures retain the last successful observation. XML parsing disables DTDs and external entities; responses are capped at 1 MB with a 25-second complete-body timeout. Feed-generation timestamps do not count as shipment changes.

Only arrival/departure events are displayed, excluding name, ownership and sanctions changes. The voyage window begins the day before the order's known departure day (`shippedOn` minus one day, UTC midnight; `shippedOn` is the origin port's own day, so UTC midnight of that day cut off the departure that set it); without that date it starts at the time the ship was linked. Historical port calls before the voyage are omitted. Voydar's public feed provides ship port calls, **not proof that the container remains on that ship**, container milestones, a verified container ETA or machine-readable coordinates. The source remains labelled `VOYDAR`, status `VESSEL_TRACKING` (Schip gekoppeld); ETA and position fields remain null. Since 2026-11-16 the ship's own arrival at the port of destination is kept as a fact about the ship (`portArrivedOn`, "schip aangekomen", never "container gelost"; section 2026-11-16 below). A separately attributed official Voydar embed may display the ship map without pretending it is a container observation.

The feed response has `configured: true` while accountless tracking is enabled, plus `shipsGoConfigured` for the optional paid integration. Setting `CONTAINER_TRACKING_ENABLED=false` disables external checks for both providers; stored observations and vessel associations remain viewable. Each row reports `source`, `vesselImo` and `automatic`; an unlinked row is not automatically tracked merely because the feature is available.

Without a source-specific stored HTTPS link, the external carrier lookup opens the accountless [track-trace container directory](https://www.track-trace.com/container). This is a manual lookup link, not a background data API.

## Accountless Evergreen date lookup

An explicitly recorded Evergreen master B/L (`EGLV` plus twelve digits) can also use the documented public [bexy MCP](https://bexy.io/mcp) endpoint, without an account or key. The server performs the MCP initialize/initialized/`track-shipment` sequence against the fixed HTTPS endpoint. It checks the echoed B/L and selected ISO container number, requires a unique container selection, and accepts only a `carrier_eta` explicitly ending at `port_of_discharge`. Container owner prefixes alone do not select a carrier.

This is a lookup of bexy's cached carrier record, not a request to refresh Evergreen. `data_as_of` is retained as `dateSourceCheckedAt`; an HTTP response received now cannot conceal that the source is older than 7 days (24 hours until 2026-11-16: bexy refreshes its copy on its own schedule, section 2026-11-16 below). Expired or mismatched records retain the saved purchase dates and show the rejection reason. The per-order/per-shipment date snapshot has its own persisted six-hour schedule; manual checks have a fifteen-minute cooldown, and the global tracking disable flag also disables these requests. Failures keep the last successful date observation. Bexy loaded/onboard status is never treated as proof of actual ship departure.

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

Tracking rows include `orderExpectedArrival` and `orderShippedOn`, read from the canonical purchase again after a refresh. `dateSource`, `dateSourceCheckedAt` and `dateError` describe the independent date observation and any reason it could not be applied. A successful ERP request does not make an old provider cache fresh: automatic date changes require a check time that lies no more than five minutes in the future and is recent enough. For a shipment source (bexy, ShipsGo) that is its own check time within 7 days (24 hours until 2026-11-16); for the vessel feed it is the ERP's own check within 24 hours.

The date synchronizer requires the exact shipment identity, both operational route ports (since 2026-11-16 compared as port areas: a port and its terminals are one place) and a current voyage anchor. Existing departure dates permit at most three days of source correction; without one, the source voyage must begin after the purchase date. Source voyages older than 180 days, impossible arrival windows, mismatched linked ships and unrecognised routes preserve the saved dates. Null or blank legacy port fields use the existing purchase domain defaults (Ningbo / Rotterdam), while the locked write still checks that the recorded route fields have not changed.

Only actual departure evidence at the configured **origin** can update `shippedOn`. ShipsGo's actual origin `DEPA` is usable; loaded/onboard evidence can anchor an ETA to the voyage but cannot invent a departure. Voydar can supply an actual origin departure only with a recognised port timezone and an unambiguous voyage; a transit stop such as Singapore on a Ningbo–Rotterdam shipment cannot replace the original shipping date. Without an existing departure date, a Voydar departure must occur after the ship was associated with the purchase. Voydar itself has no machine-readable ETA. Since 2026-11-16 two more things can set these dates (section 2026-11-16 below): the user can take the laden-on-board day of a final bill of lading over as the departure day, and an actual arrival of the linked ship at the port of destination becomes the arrival.

Database writes lock and recheck the exact reference, recorded route, order date, status, notes and existing transport dates after the network request. Concurrent manual changes win. Received, archived, deleted and concept purchases are not changed. Only `shippedOn` and `expectedArrival` (and since 2026-11-16 `port_arrived_on`) are written, with an idempotent audit entry; this code never invokes the purchase lifecycle, marks an order received, books stock or creates a payment. Arrival reminders read the resulting canonical `expectedArrival` independently.

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

The line names what is followed: when the parser of 2026-10-26 (`strictReference`) takes the text, the result is what it follows (its container, or the number behind `BL:` / `BOOKING:`), even where the rules above read the text otherwise. Two such texts: `TCNU1234565 EGHU 912345 7` (a second number with its check digit apart: `CONTAINER TCNU1234565`, not `MULTIPLE`) and a pasted non-breaking space before `BL: 814434288923 TCNU-1234565` (`CONTAINER TCNU1234565`, not the B/L). The frontend mirrors the strict parser for this (review round 1, 2026-11-10).

`ContainerTracking.reference(raw)` asks the parser of 2026-10-26 first (`strictReference`, unchanged): a text it accepts keeps its key, so no snapshot, ShipsGo registration, ship link or reminder identity moves. Only texts it rejects are read by the wider rule. Newly followed: a bare Evergreen bill, `B/L:` forms, a number followed by other text behind `BL:`, the split check digit. `TrackingDateSync.sameShipment` accepts everything it accepted before, first of all the Evergreen bill the lookup read from the text (a bare `EGLV…` got lookups but its dates were refused before).

A rewritten reference keeps the linked ship (`VesselLinkStore.rebind`, called in the order save) unless both texts are recognised and have no number in common. The order's stored port calls move with the link (their key, `ContainerTrackingService.vesselKey`, holds the reference hash), so the row keeps its last check.

### Row fields added (null when they do not apply)

`recognised {kind, number, checkDigitOk, container, evergreenBl}`; `failure`, `failureAt`, `failureDataAt`; `arrivalMovedFrom`, `arrivalMovedAt`, `arrivalMovedDays`; `manualCheckAt`; `refreshOutcome` (only on the answer of refresh and vessel). `status`, `statusLabel`, `error`, `source`, `automatic`, `checkedAt`, `updatedAt`, `nextCheckAt` and `dateError` are computed as before; the native iOS app reads those.

`GET /api/container-tracking?orderId=20` answers the same feed object with that order's row, or no row when the order is unknown, archived or has no reference. A purchase order page reads this every minute instead of the whole feed.

### A failed check

`failure` is set when the last attempt failed: the snapshot has an error and `checkedAt` differs from `updatedAt` (`Snapshot.checkFailed`). It is also set when no source answered yet, where `error` holds guidance text. `failureAt` is that attempt; `failureDataAt` is the failing snapshot's own last success (`updatedAt`), so "Gegevens bewaard van" names the time of the data that is kept, also when the date source fails beside a ship feed that answers; null when that source never answered.

Not failures: a date that could not be applied (written right after a success with the same instant in `checkDates`; keep that) stays the `dateError` line, and so does the date lookup's "nog geen actuele opgeslagen gegevens" (`BexyClient.NO_DATA_YET`) **while that bill never gave data** (a fresh bill). Once it gave data, the same answer is a failed check: `failure` "De datumbron gaf bij de laatste controle geen gegevens." (also in `dateError`, so the panel says it once), and the list, the summary and the panel show it. A received order sends no `failure`: it is never checked again.

### A manual check says what it did

`refreshOutcome`: `CHECKED` a source was asked; `COOLDOWN` the last attempt is too recent (60 s ShipsGo, 900 s Voydar and the date lookup) and `manualCheckAt` says from when a check does something again; `BUSY` the scheduled run holds the lease; `OFF` tracking is switched off; `NO_SOURCE` nothing is linked that could be asked.

The answer shows the dates the check just wrote. The service re-reads the order in its own transaction (`TrackingDateSync.reread`), also between the ship check and the date lookup, in the manual check and in the scheduled run. Outside a transaction a request keeps the order as it first read it; before 2026-11-10 the answer therefore named the old date with "de opgeslagen datum is nog niet aangepast" right after the new one was written, and a date lookup that followed a ship check which had just set the departure date was refused as "tijdens de controle gewijzigd".

### The arrival tracking moved

`purchase_order.arrival_moved_from`, `arrival_moved_at`, `arrival_notice_date`, `arrival_notice_at` (nullable; migration `docs/migrations/2026-11-10`). `TrackingDateSync.noteArrivalMove` is the only writer:

- The date that stood before tracking first replaced it stays "was" through later moves, so "3 dagen later" is always against the planning that stood. Back on that date clears the move. A first date (the order had none) is not a move.
- **A date saved through the order is the planning from then on**: `PanacheSourcingRepositories.save` clears the four columns when the saved date differs from the stored one. Tracking may still replace a hand-typed date at its next valid check, as before; that then shows as a move from the typed date. A hand date that wins until the source changes is a possible later decision, not built.
- A received order shows no move.
- The bell (`ArrivalMoveStore.notice`, through `ContainerArrivalReminders.feed`): one `CONTAINER_AANKOMST` row, no push. Title "Aankomst 15/11/2026 · 3 dagen later" and detail "was 12/11/2026": the date the order has now against the planning that stood, so the bell never names a date the order has left. It stands only while that difference is two days or more (as the tint). Its identity is the planning date plus `arrival_notice_date`, the date named last; a new identity only when tracking moves two days or more from that one (from the planning date the first time). A one-day correction changes the date in the title but not the identity or the detail, so a dismissed row stays dismissed (the frontend's dismiss key is kind, id and detail). The row's time is `arrival_notice_at`, set only when a new date is named, so a quiet correction does not bring an old row back to the top; its 14 days count from there. It also leaves at receipt, when archived, or when the date is saved by hand. It can stand beside the 10/3-day reminder of the same container.

### Time

"Today" in `TrackingDateSync.shipmentPlan` is the Brussels day (was UTC). The Voydar voyage window starts the day before `shippedOn`. Not changed: ShipsGo timestamps without an offset are read as UTC; `portZone` knows six countries, so a Voydar departure from another origin never sets the departure date; a carrier's departure day is taken in the carrier's own offset (three days of tolerance exist).

## 2026-11-16: track & trace from the bill of lading

Emre, pasting the panel's "Schip koppelen" block (IMO number only): "Moet ook met bill of lading kunnen, bekijk onze documenten en pas alles fatsoenlijk aan aub ook zodat alle track traces nu wel correct zijn." Production showed three causes on 2026-11-15. The ship could only be linked by typing an IMO number, although the bill of lading in the dossier names the ship, the voyage, the container and the bill number. A linked ship's arrival was ignored: EVER ALLY came in at Europoort on 15/11 while PO-2026-004 still said 30/11. And ports were compared by exact name, so "Europoort" was not "Rotterdam" and "Yantian" was not "Shenzhen". No provider, account or key was added, and nothing here needs ShipsGo.

### What a bill gives

`sourcing.tracking.bill.BillText` reads a PDF as lines of cells with their place on the page (PDFBox 3.0.3, glyph by glyph, at most 8 pages and 25 MB). A bill is a grid of boxes, so a value is found under its label, not by the order the words come out in. Carrier PDFs that are locked against editing read; a scan gives no lines (there is no OCR). `BillParser` turns the cells into a `Reading`: `bill` (the words "bill of lading" or "waybill" with a bill number or a container), `draft` (marked DRAFT, PROOFREAD, NOT FINAL, FOR CHECKING or CHECK COPY), `carrier`, `billNumber` (Evergreen as `EGLV` + 12 digits), `containers` (only numbers that pass the ISO 6346 check digit), `vessel`, `voyage`, `portOfLoading`, `portOfDischarge`, `onBoard` (laden or shipped on board) and `issuedOn`. Nothing is guessed: a field that is not found stays null. Both classes were verified on the Evergreen and CMA CGM bills of the running containers, drafts and finals; change them only with such documents at hand.

A draft names the planned ship, and that can change: the draft of PO-2026-004 said EVER GLOBE, its final bill EVER ALLY. So only a final bill gives a departure day and a ship name that guards anything.

### Reading a dossier

`GET /api/purchase-orders/{id}/bill-of-lading` (admin; `BillOfLadingService.overview`; reads stored documents only, asks no source) answers `{orderId, current, documents, proposals, bestDocumentId}`. `current` is the order as it stands: `trackingReference`, `shippedOn`, `status`, `vesselImo`, `vesselName` (null while no feed named the linked ship), `departurePort`, `destinationPort`, `billVessel`, `billVoyage`.

Which documents are read: PDFs (by content type or file name), never a document of a payment and never kind `PAYMENT_PROOF`, `INVOICE`, `COMMERCIAL_INVOICE` or `PROFORMA_INVOICE`. Every `BILL_OF_LADING` document comes first (at most five exist), then the other PDFs newest first, twelve in all: a telex release is often filed as "Andere". Readings are kept in memory per document (a stored document never changes; about 200 entries), so only the first look at a dossier reads its PDFs.

A document counts as a bill when the parser says so and it is filed as `BILL_OF_LADING` or names a ship: an arrival notice or a customs paper cites the container and the words "bill of lading" without one. `documents` lists every bill (`readable: true`, with its fields), every other `BILL_OF_LADING` document with a Dutch `note` ("Geen PDF: dit bestand kan niet gelezen worden.", "Scan zonder tekst: dit document kan niet gelezen worden.", "Geen bill of lading herkend in dit document."), and a scan of another kind when its label or file name says what it is (telex, release, B/L, bill, lading, waybill, 提单, 电放). Everything else is left out. The best bill stands first: final with an on-board day, then other final bills, then drafts; within those a `BILL_OF_LADING` document first, then the newest. `bestDocumentId` is that first bill.

`proposals` has one entry per readable bill, in the same order, worked out by the server:

- `reference`, `referenceChanges`: one container gives `<container> · B/L <number>`, the form the orders in production already carry (the tracker follows the container, the Evergreen date lookup finds the bill); without a container `BL: <number>`. With several containers on one bill it is the one the current text already names, never a guess, else the bill number. A text that already holds what the bill gives (compared in upper case, letters and digits only) is left as it is.
- `vessel`, `voyage` as read. `imo`, `imoFrom`: `LINKED` when the ship linked to the order is known under the bill's ship name, else `KNOWN` when exactly one ship linked before carries that name, else both null. `shipChanges`: an IMO is known and the order is not linked to it.
- `billShipChanges`: what the order keeps as "the ship on the bill" would change (below).
- `shippedOn`, `shippedOnChanges`: the on-board day of a final bill when it lies between the order date and today (Brussels). It changes an order in status BESTELD or ONDERWEG that has no departure day, one before it, or one more than three days after it: a ship sails up to a few days after loading, and the tracker may already have written that real day (the same three days the date sync allows). The status is never moved.
- `portNote`: the bill's ports beside the order's ("B/L: Yantian → Rotterdam · order: Ningbo → Rotterdam") when they are not the same places; the order's ports are not changed from here.
- `warnings`, in Dutch: a draft, a newer bill in the dossier that could not be read, several containers, a linked ship known under another name than the bill's, an on-board day on an order that is still Besteld.

### Taking a bill over

`POST /api/purchase-orders/{id}/bill-of-lading` with `{documentId, imo, expectedReference}`. `BillOfLadingService.apply` runs in one transaction, asks no source, and works the proposal out again from the stored document: of the screen it only believes which document, an IMO number typed for a ship the ERP does not know yet, and the reference the screen was opened with. It answers 404 for an unknown order or a document of another order, and 409 in Dutch for: an archived, concept or received order; a document without a readable bill; a reference that changed since the screen read it (`expectedReference`; null means none yet); an `imo` that is not seven digits with a valid check digit; an `imo` the ERP already knows under another ship name than the bill's (a mistyped number would put another ship's port calls, and its arrival, on this container).

It then writes, in this order:

1. the reference and the departure day, through `PurchaseOrderService.changeTracking`: a narrow save of these two values that shares what follows a form save (the ship link follows the reference, one history line) and none of its form rules. The form save itself never takes a departure day from outside;
2. `purchase_order.bill_vessel` and `bill_voyage`: the ship and voyage of a final bill, nothing for a draft;
3. the ship link, with the IMO typed or else the proposal's, when the order is not linked to that ship;
4. one history line "Track & trace bijgewerkt uit de bill of lading" with "B/L-schip", "B/L-reis" and "Schip (IMO)" where they changed. It is also written when only the first step changed something: that step's own line reads "Inkooporder bijgewerkt", as a form save does, and this one says where it came from. Nothing changed, no line.

The endpoint then runs the manual check (`ContainerTrackingService.refresh`; a failure there never fails the answer) and answers the order view from the order re-read in its own transaction (`TrackingDateSync.reread`). The check reads the orders outside a transaction before its date sync writes, so a plain read in that request would answer the dates from before.

A ship is linked by this apply or by hand in the panel, never in the background by its name: "Schip loskoppelen" stays what the user did.

The reference a bill is taken over in reads as its container only, while the departure step writes `BL: <number>`. `VesselLinkStore.rebind` therefore calls two texts another shipment only when both are recognised, they are read as different numbers, and neither text still shows a number of the other (letters and digits only, at least six characters). Putting the container in front of the bill number keeps the ship, its port calls and its arrival.

### A port and its terminals are one place

`PortAreas.area(nameOrCode)` normalises a port text (cut at the first comma, a country in brackets dropped, no accents, upper case, letters and digits only) and maps it to its area; a text in no area is its own area. `PortAreas.same(configured, code, name)` is true when the order's port is the area of the source's name or of its UN/LOCODE. Areas: Rotterdam (Europoort, Maasvlakte, Botlek, Pernis, Waalhaven, Eemhaven, Vlaardingen, Schiedam), Antwerp, Zeebrugge, Amsterdam, Hamburg, Bremerhaven, Felixstowe, Le Havre, Ningbo (Ningbo-Zhoushan, Beilun, Zhoushan, Meishan, Daxie, Chuanshan, Zhenhai), Shanghai (Yangshan, Waigaoqiao), Shenzhen (Yantian, Shekou, Chiwan, Mawan, Dachan Bay, Nanshan), Guangzhou (Nansha, Huangpu), Qingdao, Tianjin (Xingang) and Xiamen (Haicang), each with its codes; the class holds the list. `TrackingDateSync.samePort` delegates to it, so the carrier's dates and the ship's port calls both use it. The time zone by a code's country now also knows France.

### The linked ship's arrival counts

`purchase_order.port_arrived_on` is the day the linked ship arrived at the port of destination, read from its port calls. It is a fact about the ship the user linked ("schip aangekomen"), never "container gelost".

`TrackingDateSync.vessel` looks for it on every ship check, next to the departure: an actual `ARRIVAL` in the area of the order's destination port, in a port with a known time zone, not in the future; the day is the port's own day. Only for an order in status ONDERWEG with a departure day, and only a day 7 to 180 days after that departure (a call at the destination right after the stored departure is an earlier round of the ship). The earliest such day wins; the feed must be at most a day old and the link must still be the one it was read for. Not while the final bill names another ship than the feed does (names are the same when, without accents, in upper case and letters and digits only, they are equal or one holds the other): a mistyped IMO must not write "schip aangekomen".

What is written, with one history line "Transportdatums bijgewerkt uit Voydar · schip aangekomen in <plaats>": the arrival day, and the expected arrival on that day through the same move rules as any tracked move (the hour goes). An arrival by hand stays; then only the fact is stored. It is written on every check that finds the day, so a day an older screen put back is repaired at the next one.

While the ship is in:

- a shipment source's planning (bexy, ShipsGo) no longer moves the expected arrival: an arrival that happened beats an ETA;
- the order form save keeps the stored day whatever day it sends (the screens no longer edit that day in the form; another day is an older screen);
- "Track & trace volgen" after a date by hand lands on the ship's arrival at once (`ArrivalPlanStore.set`, `ContainerTrackingService.adoptStoredDates`);
- the bell row reads "Schip aangekomen 15/11/2026 · 15 dagen eerder" instead of "Aankomst …" while the order's day is that arrival.

It is cleared when another ship is linked or the ship is unlinked (`VesselLinkStore.save`, with a history line), when the reference is emptied or names another shipment (`VesselLinkStore.rebind`, together with the bill columns), and when the order is saved in another status than ONDERWEG or without a departure day (a status step back, the receipt). When it is cleared because the ship or the shipment changed and the expected arrival still is that day and was not set by hand, the expected arrival goes back to the planning that stood before tracking moved it (possibly none): the other ship's arrival is not this container's planning. The Aankomst sheet never clears it.

### The ship on the bill

`purchase_order.bill_vessel` and `bill_voyage` hold the ship and voyage the final bill named when it was taken over. Only the apply writes them; taking over a draft empties them. They are cleared with the arrival when the reference is emptied or names another shipment. Unlinking the ship keeps them: the bill still names that ship.

### Known ships

`VesselDirectory` turns a ship's name into an IMO number and back from what the ERP already holds: every ship ever linked has one shared feed row (`TrackingStore.vesselFeeds`: tracking number `IMO:<imo>`, id the feed's own key), and the feed's title is the ship's name. `nameOf(imo)`; `imoOf(name)` answers only when exactly one ship carries the name. A feed that never answered, or one without a title ("IMO 9893890"), names no ship. There is no keyless way to look a ship up by name (Voydar's search needs an API key), so an unknown ship's IMO number is typed once; after that the ERP knows the ship.

### The date lookup's own age

`TrackingDateSync.shipmentPlan` refused data older than 24 hours by bexy's own `data_as_of`. Bexy refreshes its copy on its own schedule (our one hit was three days old), so the rule threw away a carrier ETA of 14/11 for a ship that came in on 15/11 while the order said 30/11. A shipment source is now fresh for 7 days; the vessel feed keeps 24 hours, as that is our own check time. The other guards stand: an ETA before yesterday is never applied, the ports must be the order's (as areas), and the voyage must start within three days of the departure day.

### Row and order fields added

Tracking rows end with `portArrivedOn` (null once received), `billVessel`, `billVoyage` and `vesselDiffers` (the linked ship's feed names another ship than the bill; its arrival is not taken over). For a row with `portArrivedOn` the source's planning is not compared: `sourceArrival` is null, and `dateError` is null unless the date lookup's last check failed. The order answer's `arrival` block and the partner portal's container carry `portArrivedOn` too (the portal: null once received; a date only, no ship or reference). Older clients ignore all of these; the native iOS app reads this feed with its own copy of the rules.

Three nullable columns: `docs/migrations/2026-11-16/purchase-order-bill-and-port-arrival-postgresql.sql`, in the Dockerfile and the pre-deploy runner after the 2026-11-15 files.

PDFBox logs a full stack trace for every Chinese font whose CMap it does not ship (a draft Evergreen bill does that) while the text reads all the same: `application.properties` switches the loggers `org.apache.pdfbox.pdmodel.font` and `org.apache.fontbox` off.

### Not built, and how it was checked

Not built: a ship search by name inside the ERP (it needs a Voydar key), several ships per container (transshipment legs), OCR for scanned bills, reading the bill at the "Container vertrokken" step, changing the order's ports from the bill. An arrival stored before a final bill that names another ship is taken over stays until the ship is relinked or unlinked.

Checked by build only; the rules were walked by hand against the saved Voydar feed of EVER ALLY and the real bills of the running containers. Nothing ran against a database, Voydar, bexy or ShipsGo. Expected at the first ship check after deploy: PO-2026-004 goes from 30/11 to 15/11 and PO-2026-006 gets 15/11.

## Alternative researched

[SafeCube](https://www.safecube.ai/product/pricing) advertises a small-volume plan including API access and a [trial](https://www.safecube.ai/developers/developers). It would require its own connector and account. This implementation uses ShipsGo's documented schema; it does not imply that ShipsGo is the cheapest provider for the user's volume.
