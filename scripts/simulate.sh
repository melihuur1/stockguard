#!/bin/sh
# Runs a concurrency simulation against a running StockGuard and prints what was measured.
# Exits non-zero if the data did not stay consistent, so it also serves as an end-to-end check in CI.
#
# Usage:   scripts/simulate.sh [initial-stock] [concurrent-requests] [units-per-request]
# Example: STOCKGUARD_ADMIN_PASSWORD=... scripts/simulate.sh 1 100
set -eu

STOCK="${1:-1}"
REQUESTS="${2:-100}"
UNITS="${3:-1}"
BASE_URL="${STOCKGUARD_URL:-http://localhost:8095}"
ADMIN_USERNAME="${STOCKGUARD_ADMIN_USERNAME:-admin}"
: "${STOCKGUARD_ADMIN_PASSWORD:?Set STOCKGUARD_ADMIN_PASSWORD to the administrator password}"

# Reads one top-level field out of the JSON response without requiring jq.
field() {
    printf '%s' "$2" | sed -n 's/.*"'"$1"'":\([^,}]*\).*/\1/p' | head -n 1 | tr -d '"'
}

login=$(curl -sS -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$STOCKGUARD_ADMIN_PASSWORD\"}")
token=$(field accessToken "$login")
if [ -z "$token" ]; then
    echo "Login failed: $login" >&2
    exit 2
fi

report=$(curl -sS -X POST "$BASE_URL/api/v1/simulations/reservations" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' \
    -d "{\"initialStock\":$STOCK,\"concurrentRequests\":$REQUESTS,\"quantityPerRequest\":$UNITS}")
consistent=$(field dataConsistent "$report")
if [ -z "$consistent" ]; then
    echo "Simulation failed: $report" >&2
    exit 2
fi

instances=$(printf '%s' "$report" | sed -n 's/.*"requestsPerInstance":{\([^}]*\)}.*/\1/p' | tr -d '"' | sed 's/,/, /g; s/:/: /g')

cat <<EOF
Initial stock:           $(field initialStock "$report")
Concurrent requests:     $(field concurrentRequests "$report")
Successful reservations: $(field successfulReservations "$report")
Rejected reservations:   $(field rejectedReservations "$report")
Unexpected failures:     $(field unexpectedFailures "$report")
Rows in database:        $(field reservationRowsInDatabase "$report")
Stock after the race:    total $(field totalQuantity "$report"), available $(field availableQuantity "$report"), reserved $(field reservedQuantity "$report")
Oversold units:          $(field oversoldUnits "$report")
Negative stock counters: $(field negativeStockCounters "$report")
Requests per instance:   $instances
Duration:                $(field durationMillis "$report") ms
EOF

if [ "$consistent" = "true" ]; then
    echo "Data consistency:        PRESERVED"
else
    echo "Data consistency:        VIOLATED"
    printf '%s\n' "$report" >&2
    exit 1
fi
