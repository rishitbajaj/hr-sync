#!/usr/bin/env bash
# Run the README walkthrough against a stack started from this repo and print each response.
# Requires .env, a healthy `docker compose` app, and python3.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

if [[ ! -f .env ]]; then
  echo "Copy .env.example to .env and set every value before running this script." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

: "${HR_SYNC_BOOTSTRAP_ADMIN_TENANT:?}"
: "${HR_SYNC_BOOTSTRAP_ADMIN_USERNAME:?}"
: "${HR_SYNC_BOOTSTRAP_ADMIN_PASSWORD:?}"
: "${HR_SYNC_BOOTSTRAP_OPERATOR_USERNAME:?}"
: "${HR_SYNC_BOOTSTRAP_OPERATOR_PASSWORD:?}"

base=${BASE_URL:-http://localhost:8080}
tenant=$HR_SYNC_BOOTSTRAP_ADMIN_TENANT

step() {
  printf '\n=== %s ===\n' "$1"
}

token_of() {
  local username=$1 password=$2
  curl -sS -X POST "$base/auth/token" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${username}\",\"password\":\"${password}\"}"
}

step "login admin"
admin_body=$(token_of "$HR_SYNC_BOOTSTRAP_ADMIN_USERNAME" "$HR_SYNC_BOOTSTRAP_ADMIN_PASSWORD")
printf '%s\n' "$admin_body"
token=$(printf '%s' "$admin_body" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

step "login operator"
operator_body=$(token_of "$HR_SYNC_BOOTSTRAP_OPERATOR_USERNAME" "$HR_SYNC_BOOTSTRAP_OPERATOR_PASSWORD")
printf '%s\n' "$operator_body"
operator=$(printf '%s' "$operator_body" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

step "office"
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "insert into office (id, tenant_id, code) values (gen_random_uuid(), '${tenant}', 'BLR') on conflict (tenant_id, code) do nothing;"

step "batch with a bad phone"
curl -sS -X POST "$base/sync/events" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "{\"events\":[
    {\"tenantId\":\"$tenant\",\"employeeId\":\"walk-bad\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Bad\",\"email\":\"bad@example.com\",\"phone\":\"12345\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}},
    {\"tenantId\":\"$tenant\",\"employeeId\":\"walk-1\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"phone\":\"+15550000001\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}
  ]}"
printf '\n'

step "replay the applied create"
curl -sS -X POST "$base/sync/events" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$tenant\",\"employeeId\":\"walk-1\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"phone\":\"+15550000001\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}]}"
printf '\n'

step "version 3"
curl -sS -X POST "$base/sync/events" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$tenant\",\"employeeId\":\"walk-1\",\"sourceVersion\":3,\"type\":\"UPDATE\",\"fields\":{\"name\":\"Ada Three\",\"shift\":\"NIGHT\"}}]}"
printf '\n'

step "version 2 after version 3"
curl -sS -X POST "$base/sync/events" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$tenant\",\"employeeId\":\"walk-1\",\"sourceVersion\":2,\"type\":\"UPDATE\",\"fields\":{\"name\":\"Ada Two\",\"shift\":\"DAY\"}}]}"
printf '\n'

step "employee read"
curl -sS "$base/employees/walk-1" -H "Authorization: Bearer $token"
printf '\n'

step "stored name and version"
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "select name, hr_version, shift from employee where tenant_id = '${tenant}' and employee_id = 'walk-1';"

step "transport field"
curl -sS -X POST "$base/sync/transport-events" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$tenant\",\"employeeId\":\"walk-1\",\"sourceVersion\":1,\"type\":\"UPDATE\",\"fields\":{\"pickupPoint\":\"Gate 4\",\"specialAssistance\":\"wheelchair\"}}]}"
printf '\n'

step "hr event does not take the transport fields"
curl -sS -X POST "$base/sync/events" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$tenant\",\"employeeId\":\"walk-1\",\"sourceVersion\":4,\"type\":\"UPDATE\",\"fields\":{\"name\":\"Ada Owned\",\"pickupPoint\":\"Gate 9\",\"specialAssistance\":\"none\"}}]}"
printf '\n'

step "stored ownership"
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "select name, hr_version, pickup_point, transport_version from employee where tenant_id = '${tenant}' and employee_id = 'walk-1';"

step "truncated reconciliation"
printf '%s\n' 'tenantId,employeeId,sourceVersion,name,email,phone,manager,officeCode,shift' \
  "${tenant},walk-new,1,New,new@example.com,+15550000002,M1,BLR,DAY" > /tmp/hr-sync-truncated.csv
run=$(curl -sS -X POST "$base/reconciliation/runs?tenantId=${tenant}" \
  -H "Authorization: Bearer $token" -H 'Content-Type: text/csv' \
  --data-binary @/tmp/hr-sync-truncated.csv)
printf '%s\n' "$run"
run_id=$(printf '%s' "$run" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

step "operator approval"
curl -sS -X POST "$base/reconciliation/runs/${run_id}/approve?tenantId=${tenant}" \
  -H "Authorization: Bearer $operator"
printf '\n'

step "deactivated employee"
curl -sS "$base/employees/walk-1" -H "Authorization: Bearer $token"
printf '\n'

if [[ "${SPRING_PROFILES_ACTIVE:-}" == *demo* ]]; then
  step "seed trip and booking"
  curl -sS -X POST "$base/demo/trips" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"${tenant}\",\"tripId\":\"trip-walk\",\"capacity\":4}"
  printf '\n'
  curl -sS -X POST "$base/demo/bookings" -H 'Content-Type: application/json' \
    -d "{\"bookingId\":\"book-walk\",\"tenantId\":\"${tenant}\",\"employeeId\":\"walk-book\",\"tripId\":\"trip-walk\",\"startsAt\":\"2026-12-01T09:00:00Z\"}"
  printf '\n'

  step "create and deactivate the rider"
  curl -sS -X POST "$base/sync/events" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"events\":[{\"tenantId\":\"$tenant\",\"employeeId\":\"walk-book\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Rider\",\"email\":\"rider@example.com\",\"phone\":\"+15550000003\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}]}"
  printf '\n'
  curl -sS -X POST "$base/sync/events" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"events\":[{\"tenantId\":\"$tenant\",\"employeeId\":\"walk-book\",\"sourceVersion\":2,\"type\":\"DEACTIVATE\"}]}"
  printf '\n'

  step "wait for the booking cancellation"
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    board=$(curl -sS "$base/demo/bookings")
    if printf '%s' "$board" | python3 -c 'import json,sys; board=json.load(sys.stdin); raise SystemExit(0 if any(row["cancelled"] for row in board["bookings"]) else 1)'; then
      printf '%s\n' "$board"
      break
    fi
    sleep 1
  done
else
  step "demo profile is off"
  echo "SPRING_PROFILES_ACTIVE does not include demo, so /demo/** is not registered. Set it in .env and recreate the app to cancel a booking."
fi

step "prometheus"
curl -sS -D - -o /tmp/hr-sync-metrics.txt \
  -H "Authorization: Bearer $operator" \
  -H 'Accept: text/plain' \
  "$base/actuator/prometheus" | head -n 20
echo "scrape bytes: $(wc -c < /tmp/hr-sync-metrics.txt)"
grep -E '^(outbox_|# EOF)' /tmp/hr-sync-metrics.txt | head -n 20 || true
