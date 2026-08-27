# Public pickup locations

Run `public-pickup-locations-postgresql.sql` before the matching application
release when an environment uses `DB_SCHEMA_STRATEGY=validate`. The default
`update` strategy can add the nullable runtime columns itself, but applying the
script first also installs explicit defaults and non-null constraints for the
two control fields.

## Admin contract

`GET/POST/PUT /api/stock/locations` includes these additive fields:

- `publicPickupPoint`
- `publicPickupLabel`
- `publicPickupAddress`
- `publicPickupInstructions`
- `publicPickupPosition`

Enabling a point requires a public label and address. An inactive location is
never exposed publicly, even if its public-pickup switch remains enabled.

## Public quote contract

`GET /api/v1/public/quotes/configuration` returns `pickupLocations` with
`id`, `label`, `address`, `instructions`, and `position`. `PICKUP` appears in
`fulfillmentMethods` only when at least one location is available.

Both preview and submission accept `pickupLocationId`. One available location
is selected automatically for compatibility with the original single-pickup
client. With several locations the field is required. Missing, inactive, or
private locations return a field error on `pickupLocationId`.

Every pickup preview has zero freight, handling, and shipping. Submission stores
an immutable snapshot on the ERP sales order as `pickupLocation`; editing the
stock location later does not rewrite an existing request.
