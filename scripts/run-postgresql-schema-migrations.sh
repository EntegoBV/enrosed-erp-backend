#!/bin/sh
set -eu

# psql reads these variables without putting database credentials in the
# process arguments or deployment log. Railway supplies the PG* values; the
# DB_* fallbacks mirror application.properties for non-Railway deployments.
: "${PGHOST:?PGHOST is required for the schema migration}"
: "${PGPORT:?PGPORT is required for the schema migration}"
export PGDATABASE="${PGDATABASE:-enrosed}"
export PGUSER="${PGUSER:-${DB_USERNAME:-enrosed}}"
export PGPASSWORD="${PGPASSWORD:-${DB_PASSWORD:-}}"
: "${PGPASSWORD:?PGPASSWORD or DB_PASSWORD is required for the schema migration}"

# One session-level lock serializes overlapping deployments. Each migration
# owns its transaction: an earlier additive change may safely remain committed
# when a later one fails, while ON_ERROR_STOP prevents the release from starting.
exec psql \
    --no-psqlrc \
    --no-password \
    --set=ON_ERROR_STOP=1 \
    --command="select pg_advisory_lock(hashtext('enrosed'), hashtext('schema-migrations'));" \
    --file=/app/migrations/public-pickup-locations-postgresql.sql \
    --file=/app/migrations/product-supplier-agreement-photos-postgresql.sql \
    --file=/app/migrations/product-line-discount-target-postgresql.sql \
    --file=/app/migrations/document-media-manager-postgresql.sql \
    --file=/app/migrations/media-folders-shares-web-postgresql.sql \
    --file=/app/migrations/sales-order-column-lengths-postgresql.sql \
    --file=/app/migrations/website-visits-postgresql.sql \
    --file=/app/migrations/purchase-line-issue-note-postgresql.sql \
    --file=/app/migrations/purchase-order-inspection-cost-postgresql.sql \
    --file=/app/migrations/purchase-order-other-costs-postgresql.sql \
    --file=/app/migrations/orders-archived-at-postgresql.sql \
    --file=/app/migrations/sales-order-extra-lines-postgresql.sql
