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

# A restored database can need a short time to publish its private DNS record
# and accept connections. Retry only this read-only probe, never a migration:
# an earlier migration file may already have committed before a later failure.
wait_for_database() (
    db_wait_attempts="${ENROSED_DB_WAIT_ATTEMPTS:-30}"
    db_wait_delay="${ENROSED_DB_WAIT_DELAY_SECONDS:-2}"
    export PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-3}"
    case "$db_wait_attempts:$db_wait_delay:$PGCONNECT_TIMEOUT" in
        *[!0-9:]*|:*|*::*|*:)
            echo "Invalid database readiness timing configuration" >&2
            exit 1 ;;
    esac
    if [ "$db_wait_attempts" -lt 1 ] || [ "$db_wait_attempts" -gt 60 ] \
            || [ "$db_wait_delay" -gt 5 ] || [ "$PGCONNECT_TIMEOUT" -lt 1 ] \
            || [ "$PGCONNECT_TIMEOUT" -gt 10 ]; then
        echo "Database readiness timing exceeds the supported bounds" >&2
        exit 1
    fi
    db_probe_error=$(mktemp "${TMPDIR:-/tmp}/enrosed-db-readiness.XXXXXX")
    trap 'rm -f "$db_probe_error"' 0
    trap 'exit 1' 1 2 15
    db_attempt=1
    while :; do
        if LC_ALL=C psql --no-psqlrc --no-password --set=ON_ERROR_STOP=1 \
                --tuples-only --no-align --command='SELECT 1;' \
                >/dev/null 2>"$db_probe_error"; then
            echo "Database connection ready; starting schema migrations once"
            exit 0
        else
            db_probe_status=$?
        fi
        # Authentication, missing database/role, SQL, and other configuration
        # failures are not transient. Never log raw errors or credential values.
        if ! grep -Eqi 'could not translate host name|name or service not known|temporary failure in name resolution|connection refused|connection timed out|timeout expired|network is unreachable|no route to host|database system is starting up|database system is in recovery mode|database system is shutting down|the database system is not yet accepting connections|server closed the connection unexpectedly' "$db_probe_error"; then
            echo "Database readiness failed with a non-transient error (psql exit $db_probe_status); no migrations started" >&2
            exit "$db_probe_status"
        fi
        if [ "$db_attempt" -ge "$db_wait_attempts" ]; then
            echo "Database remains unavailable after $db_wait_attempts readiness attempts; no migrations started" >&2
            exit "$db_probe_status"
        fi
        echo "Database DNS/network/startup not ready; retrying ($db_attempt/$db_wait_attempts)" >&2
        sleep "$db_wait_delay"
        db_attempt=$((db_attempt + 1))
    done
)
wait_for_database

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
    --file=/app/migrations/sales-order-extra-lines-postgresql.sql \
    --file=/app/migrations/product-photo-lead-roles-postgresql.sql \
    --file=/app/migrations/category-photos-postgresql.sql \
    --file=/app/migrations/stock-movement-purchase-order-postgresql.sql \
    --file=/app/migrations/sales-order-partner-deal-postgresql.sql \
    --file=/app/migrations/partner-customers-and-settlements-postgresql.sql \
    --file=/app/migrations/sales-channel-and-company-costs-postgresql.sql \
    --file=/app/migrations/product-cost-history-postgresql.sql \
    --file=/app/migrations/push-subscription-username-postgresql.sql \
    --file=/app/migrations/recurring-costs-and-bank-balances-postgresql.sql \
    --file=/app/migrations/sales-line-cost-snapshot-postgresql.sql \
    --file=/app/migrations/media-link-company-cost-postgresql.sql \
    --file=/app/migrations/customer-fiscal-representative-postgresql.sql \
    --file=/app/migrations/purchase-order-payment-split-postgresql.sql \
    --file=/app/migrations/company-number-prefixes-postgresql.sql \
    --file=/app/migrations/company-partner-number-series-postgresql.sql \
    --file=/app/migrations/purchase-payment-settles-postgresql.sql \
    --file=/app/migrations/purchase-payment-instalment-postgresql.sql \
    --file=/app/migrations/purchase-payment-payees-postgresql.sql \
    --file=/app/migrations/purchase-order-partner-postgresql.sql \
    --file=/app/migrations/purchase-line-extra-share-postgresql.sql \
    --file=/app/migrations/purchase-order-separate-allocation-postgresql.sql \
    --file=/app/migrations/partner-invoices-and-incoming-payments-postgresql.sql \
    --file=/app/migrations/bank-balance-as-of-postgresql.sql \
    --file=/app/migrations/bank-movements-and-refunds-postgresql.sql \
    --file=/app/migrations/partner-schedules-and-partial-settlements-postgresql.sql \
    --file=/app/migrations/advance-quote-arrangements-postgresql.sql \
    --file=/app/migrations/partner-advance-contents-postgresql.sql \
    --file=/app/migrations/partner-advance-financing-basis-postgresql.sql \
    --file=/app/migrations/partner-invoice-declaration-postgresql.sql \
    --file=/app/migrations/deleted-items-postgresql.sql \
    --file=/app/migrations/sales-splits-postgresql.sql \
    --file=/app/migrations/sales-line-availability-postgresql.sql \
    --file=/app/migrations/container-cost-markup-mode-postgresql.sql \
    --file=/app/migrations/greek-language-postgresql.sql \
    --file=/app/migrations/shared-supplier-agreements-postgresql.sql \
    --file=/app/migrations/website-quote-settings-postgresql.sql \
    --file=/app/migrations/family-catalogue-photo-choices-postgresql.sql \
    --file=/app/migrations/product-20ft-capacity-postgresql.sql \
    --file=/app/migrations/product-family-localized-tags-postgresql.sql \
    --file=/app/migrations/product-shopify-identities-postgresql.sql \
    --file=/app/migrations/product-short-skus-postgresql.sql \
    --file=/app/migrations/product-localized-tags-seed-postgresql.sql \
    --file=/app/migrations/product-sales-unit-postgresql.sql \
    --file=/app/migrations/family-website-quote-photo-postgresql.sql \
    --file=/app/migrations/product-unit-key-postgresql.sql
