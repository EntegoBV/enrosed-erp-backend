# Container arrival reminders (TST rollout)

The server checks saved purchase arrival dates every five minutes. Active, non-archived BESTELD/ONDERWEG purchases with a tracking reference and no receipt date are eligible. The calculation uses calendar days in Europe/Brussels, so daylight-saving changes do not shift the thresholds.

- 4–10 days remaining: one 10-day reminder per purchase and tracking-reference voyage.
- 0–3 days remaining: one 3-day reminder. If a purchase is first seen here, the older 10-day reminder is skipped.
- No reminder for past dates, concept, received or archived orders.
- New notices and device delivery occur between 09:00 and 21:00 Brussels time. A restart or changed ETA is picked up on the next eligible scan.
- ETA corrections do not create duplicate notices for an already recorded threshold. Pending deliveries are checked against the latest saved order immediately before sending. An ETA moving outside its window suspends the notice. The in-app feed always describes the current saved date. After a 3-day notice exists, a later delay does not create a 10-day notice for that voyage.

Dates come from the canonical purchase header. Reliable tracking-date synchronization may update those fields; manually entered dates remain valid planning fallback. Wording therefore says `volgens de huidige planning`, never that a vessel ETA proves container arrival. Reminder storage does not change order status, inventory, payments or dates.

`GET /api/container-arrival-reminders/preview` is an authenticated, read-only dry run returning current candidates. It never queues or sends. The bell's existing `/api/notifications` feed includes `CONTAINER_AANKOMST` with a `url` pointing to `/purchasing/{id}?tracking=1` and a stable `notificationId` for that voyage and threshold. Dismissing the 10-day notice does not dismiss the later 3-day notice. Daily countdown wording stays dismissed; a changed ETA in the detail can make the corrected planning visible again, without sending a second push for that threshold.

Configuration:

- `CONTAINER_ARRIVAL_REMINDERS_ENABLED=true` by default controls stored reminders and in-app feed. `CONTAINER_TRACKING_ENABLED=false` also pauses them.
- `CONTAINER_ARRIVAL_PUSH_ENABLED=false` by default permits a safe first preview. Enable explicitly on TST after reviewing the preview.
- Device pushes also require the existing `ENROSED_PUSH_ENABLED=true` and an already registered push subscription. No subscription or permission is created by this feature. There is no email or SMS delivery.

A stable notice identity plus a durable lease serializes workers. Identity uses the purchase ID, normalized valid container (when uniquely present), and threshold; adding a B/L or changing case/spacing does not repeat a reminder for that same container. Without a container, an explicitly typed B/L/booking is normalized, otherwise case and whitespace in the reference are normalized. Each registered device has independent persisted attempts and provider acceptance (maximum five attempts). Stable push tags/topics replace retransmitted copies on the device. As with any external push provider, a crash between provider acceptance and recording can cause a retry; provider acceptance is not proof that the person saw the notification. In-app reminders remain available even without a push-capable device.

Apply the additive `docs/migrations/2026-10-26/container-arrival-reminders-postgresql.sql` where automatic Hibernate schema update is not used. First deploy only to TEST, verify preview before enabling push, and never create fictitious production/test purchases to manufacture a real notification proof.
