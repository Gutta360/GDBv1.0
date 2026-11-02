#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Gate 2 is deliberately loopback-only. It creates synthetic staging records
# through the already-running GDB API and does not touch routes, tablets, or
# any existing application service.

BASE_URL="http://127.0.0.1:8192"
CONFIG_DIR="/etc/gdb-staging"
WORK="$(mktemp -d /tmp/gdb-gate2-acceptance.XXXXXX)"
EVIDENCE="$WORK/evidence.log"
CALL_ID=0
ADMIN_COOKIE=""
ADMIN_TOKEN=""

touch "$EVIDENCE"
chmod 700 "$WORK"

record() {
  printf '%s\n' "$*" | tee -a "$EVIDENCE"
}

json_value() {
  local file="$1" path="$2"
  python3 - "$file" "$path" <<'PY'
import json
import sys

value = json.load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split('.'):
    value = value[int(part)] if part.isdigit() else value[part]
if isinstance(value, (dict, list)):
    print(json.dumps(value, separators=(',', ':')))
elif value is None:
    raise SystemExit(1)
elif isinstance(value, bool):
    print(str(value).lower())
else:
    print(value)
PY
}

json_length() {
  local file="$1" path="$2"
  python3 - "$file" "$path" <<'PY'
import json
import sys

value = json.load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split('.'):
    value = value[int(part)] if part.isdigit() else value[part]
print(len(value))
PY
}

json_has_operations() {
  local file="$1" first="$2" second="$3"
  python3 - "$file" "$first" "$second" <<'PY'
import json
import sys

events = json.load(open(sys.argv[1], encoding="utf-8"))["events"]
operations = {event["operation"] for event in events}
raise SystemExit(0 if sys.argv[2] in operations and sys.argv[3] in operations else 1)
PY
}

fail() {
  record "GATE2 FAIL: $*"
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

expect_status() {
  local label="$1" expected="$2" actual="$3"
  record "$label status=$actual"
  [ "$actual" = "$expected" ] || fail "$label expected HTTP $expected, got $actual"
}

expect_json() {
  local label="$1" file="$2" path="$3" expected="$4"
  local actual
  actual="$(json_value "$file" "$path")" || fail "$label response did not contain $path"
  [ "$actual" = "$expected" ] || fail "$label expected $path=$expected, got $actual"
  record "$label $path=$actual"
}

request() {
  local method="$1" path="$2" body_file="$3" auth="$4"
  CALL_ID=$((CALL_ID + 1))
  LAST_BODY="$WORK/response-$CALL_ID"
  LAST_HEADERS="$WORK/headers-$CALL_ID"
  local -a args=(
    --silent --show-error --connect-timeout 5 --max-time 30
    --dump-header "$LAST_HEADERS" --output "$LAST_BODY" --write-out '%{http_code}'
    --request "$method" "$BASE_URL$path"
  )

  if [ "$body_file" != "-" ]; then
    args+=(--header 'Content-Type: application/json' --data-binary "@$body_file")
  fi
  case "$auth" in
    admin) args+=(--header "Cookie: GDB_ADMIN_SESSION=$ADMIN_COOKIE") ;;
    device-a) args+=(--header "Authorization: Bearer $DEVICE_A_TOKEN") ;;
    device-b) args+=(--header "Authorization: Bearer $DEVICE_B_TOKEN") ;;
    device-c) args+=(--header "Authorization: Bearer $DEVICE_C_TOKEN") ;;
    device-b-rotated) args+=(--header "Authorization: Bearer $DEVICE_B_ROTATED_TOKEN") ;;
    none) ;;
    *) fail "unknown request credential selector: $auth" ;;
  esac

  if ! LAST_STATUS="$(curl "${args[@]}")"; then
    fail "$method $path transport failure"
  fi
}

write_body() {
  local name="$1"
  BODY_FILE="$WORK/request-$name.json"
  cat > "$BODY_FILE"
}

new_uuid() {
  python3 -c 'import uuid; print(uuid.uuid4())'
}

cleanup() {
  local rc=$?
  # End the synthetic admin session when possible. No response body is shown.
  if [ -n "${ADMIN_COOKIE:-}" ]; then
    curl --silent --show-error --max-time 10 --output /dev/null \
      --request POST --header "Cookie: GDB_ADMIN_SESSION=$ADMIN_COOKIE" \
      "$BASE_URL/api/v1/auth/admin/logout" || true
  fi
  unset ADMIN_TOKEN ADMIN_COOKIE DEVICE_A_TOKEN DEVICE_B_TOKEN DEVICE_C_TOKEN DEVICE_B_ROTATED_TOKEN
  rm -f "$WORK"/request-* "$WORK"/response-* "$WORK"/headers-*
  if [ "$rc" -eq 0 ]; then
    rm -f "$EVIDENCE"
    rmdir "$WORK"
  else
    record "GATE2: non-secret evidence retained at $EVIDENCE"
  fi
  exit "$rc"
}
trap cleanup EXIT

for command_name in curl python3 sudo awk sed date sha256sum base64; do
  require_command "$command_name"
done

if ! sudo -n true >/dev/null 2>&1; then
  record "GATE2: sudo credential validation requires the operator's interactive sudo session"
fi

ADMIN_TOKEN="$(sudo awk -F= '$1 == "GDB_ADMIN_BOOTSTRAP_TOKEN" {sub(/^[^=]*=/, ""); print; exit}' "$CONFIG_DIR/roles.env")"
[ -n "$ADMIN_TOKEN" ] || fail "staging bootstrap token is unavailable"

request GET /actuator/health - none
expect_status "backend health" 200 "$LAST_STATUS"

RUN_ID="$(date -u +%Y%m%d%H%M%S)"
ADMIN_USERNAME="gate2admin"
ADMIN_PASSWORD="G2-$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"

write_body bootstrap <<EOF
{"bootstrapToken":"$ADMIN_TOKEN","username":"$ADMIN_USERNAME","password":"$ADMIN_PASSWORD"}
EOF
request POST /api/v1/auth/admin/bootstrap "$BODY_FILE" none
expect_status "administrator bootstrap" 201 "$LAST_STATUS"
ADMIN_COOKIE="$(sed -n 's/^[Ss]et-[Cc]ookie: GDB_ADMIN_SESSION=\([^;]*\).*/\1/p' "$LAST_HEADERS" | tail -n 1)"
[ -n "$ADMIN_COOKIE" ] || fail "administrator bootstrap did not return a session cookie"

write_body login <<EOF
{"username":"$ADMIN_USERNAME","password":"$ADMIN_PASSWORD"}
EOF
request POST /api/v1/auth/admin/login "$BODY_FILE" none
expect_status "administrator login" 200 "$LAST_STATUS"
LOGIN_COOKIE="$(sed -n 's/^[Ss]et-[Cc]ookie: GDB_ADMIN_SESSION=\([^;]*\).*/\1/p' "$LAST_HEADERS" | tail -n 1)"
[ -n "$LOGIN_COOKIE" ] || fail "administrator login did not return a session cookie"
ADMIN_COOKIE="$LOGIN_COOKIE"

request GET /api/v1/auth/admin/me - admin
expect_status "administrator session" 200 "$LAST_STATUS"
request GET /api/v1/admin/monitoring/dashboard - admin
expect_status "admin dashboard" 200 "$LAST_STATUS"

APP_A="G2A$RUN_ID"
APP_B="G2B$RUN_ID"
TENANT_A="TA$RUN_ID"
TENANT_B="TB$RUN_ID"

write_body app-a <<EOF
{"appCode":"$APP_A","displayName":"Gate 2 Synthetic A","versionMetadata":"1.0.0","permittedDatasets":"[]","snapshotPolicy":"{}"}
EOF
request POST /api/v1/admin/applications "$BODY_FILE" admin
expect_status "application A creation" 201 "$LAST_STATUS"
APP_A_ID="$(json_value "$LAST_BODY" appId)" || fail "application A id missing"

write_body tenant-a <<EOF
{"appId":"$APP_A_ID","tenantCode":"$TENANT_A","tenantName":"Gate 2 Synthetic Tenant A"}
EOF
request POST /api/v1/admin/tenants "$BODY_FILE" admin
expect_status "tenant A creation" 201 "$LAST_STATUS"
TENANT_A_ID="$(json_value "$LAST_BODY" tenantId)" || fail "tenant A id missing"

request POST "/api/v1/admin/tenants/$TENANT_A_ID/enrollment-codes" - admin
expect_status "tenant A enrollment code" 201 "$LAST_STATUS"
ENROLL_A="$(json_value "$LAST_BODY" enrollmentCode)" || fail "tenant A enrollment code missing"

APP_ID_LOWER="$(printf '%s' "$APP_A" | tr '[:upper:]' '[:lower:]')"
write_body device-a <<EOF
{"enrollmentCode":"$ENROLL_A","deviceName":"Gate 2 Synthetic Device A","applicationId":"com.gdb.$APP_ID_LOWER","applicationVersion":"1.0.0","platform":"ANDROID"}
EOF
request POST /api/v1/devices/register "$BODY_FILE" none
expect_status "device A registration" 201 "$LAST_STATUS"
DEVICE_A_ID="$(json_value "$LAST_BODY" deviceId)" || fail "device A id missing"
DEVICE_A_TOKEN="$(json_value "$LAST_BODY" deviceToken)" || fail "device A token missing"

request POST /api/v1/devices/register "$BODY_FILE" none
expect_status "one-use enrollment code rejection" 401 "$LAST_STATUS"

request POST "/api/v1/admin/tenants/$TENANT_A_ID/enrollment-codes" - admin
expect_status "second device enrollment code" 201 "$LAST_STATUS"
ENROLL_B="$(json_value "$LAST_BODY" enrollmentCode)" || fail "second enrollment code missing"
write_body device-b <<EOF
{"enrollmentCode":"$ENROLL_B","deviceName":"Gate 2 Synthetic Device B","applicationId":"com.gdb.$APP_ID_LOWER","applicationVersion":"1.0.0","platform":"ANDROID"}
EOF
request POST /api/v1/devices/register "$BODY_FILE" none
expect_status "device B registration" 201 "$LAST_STATUS"
DEVICE_B_ID="$(json_value "$LAST_BODY" deviceId)" || fail "device B id missing"
DEVICE_B_TOKEN="$(json_value "$LAST_BODY" deviceToken)" || fail "device B token missing"

request GET /api/v1/sync/status - none
expect_status "unauthenticated sync rejection" 401 "$LAST_STATUS"
request GET /api/v1/sync/status - device-a
expect_status "device A sync status" 200 "$LAST_STATUS"

ENTITY_ID="$(new_uuid)"
EVENT_CREATE_ID="$(new_uuid)"
EVENT_CREATE_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
EVENT_CREATE="{\"eventId\":\"$EVENT_CREATE_ID\",\"entityType\":\"ORDER\",\"entityId\":\"$ENTITY_ID\",\"operation\":\"CREATE\",\"baseVersion\":0,\"entityVersion\":1,\"payloadVersion\":1,\"payload\":{\"name\":\"gate2-synthetic-001\",\"active\":true},\"createdAt\":\"$EVENT_CREATE_TIME\"}"

push_event() {
  local name="$1" event_json="$2" auth="$3"
  local batch_id request_device_id
  batch_id="$(new_uuid)"
  case "$auth" in
    device-a) request_device_id="$DEVICE_A_ID" ;;
    device-b) request_device_id="$DEVICE_B_ID" ;;
    device-c) request_device_id="$DEVICE_C_ID" ;;
    *) fail "push_event requires a device credential" ;;
  esac
  write_body "$name" <<EOF
{"contractVersion":1,"appCode":"$APP_A","tenantId":"$TENANT_A_ID","deviceId":"$request_device_id","batchId":"$batch_id","events":[$event_json]}
EOF
  request POST /api/v1/sync/push "$BODY_FILE" "$auth"
}

push_event create "$EVENT_CREATE" device-a
expect_status "initial event push" 200 "$LAST_STATUS"
expect_json "initial event push" "$LAST_BODY" results.0.status ACCEPTED

request GET "/api/v1/sync/pull?after=0&limit=1" - device-b
expect_status "device B initial pull" 200 "$LAST_STATUS"
expect_json "device B initial pull" "$LAST_BODY" events.0.entityId "$ENTITY_ID"
CURSOR="$(json_value "$LAST_BODY" nextCursor)" || fail "initial pull cursor missing"
expect_json "device B initial pull" "$LAST_BODY" hasMore false

request GET "/api/v1/sync/pull?after=$CURSOR&limit=100" - device-b
expect_status "stable cursor replay pull" 200 "$LAST_STATUS"
expect_json "stable cursor replay pull" "$LAST_BODY" nextCursor "$CURSOR"
expect_json "stable cursor replay pull" "$LAST_BODY" hasMore false
expect_json "stable cursor replay pull" "$LAST_BODY" events '[]'

push_event duplicate "$EVENT_CREATE" device-a
expect_status "duplicate event push" 200 "$LAST_STATUS"
expect_json "duplicate event push" "$LAST_BODY" results.0.status DUPLICATE

EVENT_UPDATE_ID="$(new_uuid)"
EVENT_UPDATE="{\"eventId\":\"$EVENT_UPDATE_ID\",\"entityType\":\"ORDER\",\"entityId\":\"$ENTITY_ID\",\"operation\":\"UPDATE\",\"baseVersion\":1,\"entityVersion\":2,\"payloadVersion\":1,\"payload\":{\"name\":\"gate2-synthetic-002\",\"active\":true},\"createdAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
push_event update "$EVENT_UPDATE" device-a
expect_status "concurrent update winner" 200 "$LAST_STATUS"
expect_json "concurrent update winner" "$LAST_BODY" results.0.status ACCEPTED

EVENT_STALE_ID="$(new_uuid)"
EVENT_STALE="{\"eventId\":\"$EVENT_STALE_ID\",\"entityType\":\"ORDER\",\"entityId\":\"$ENTITY_ID\",\"operation\":\"UPDATE\",\"baseVersion\":1,\"entityVersion\":2,\"payloadVersion\":1,\"payload\":{\"name\":\"gate2-stale-update\",\"active\":true},\"createdAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
push_event stale "$EVENT_STALE" device-b
expect_status "stale concurrent update" 200 "$LAST_STATUS"
expect_json "stale concurrent update" "$LAST_BODY" results.0.status CONFLICT

EVENT_DELETE_ID="$(new_uuid)"
EVENT_DELETE="{\"eventId\":\"$EVENT_DELETE_ID\",\"entityType\":\"ORDER\",\"entityId\":\"$ENTITY_ID\",\"operation\":\"DELETE\",\"baseVersion\":2,\"entityVersion\":3,\"payloadVersion\":1,\"payload\":{\"name\":\"gate2-synthetic-002\",\"active\":false},\"createdAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
push_event delete "$EVENT_DELETE" device-a
expect_status "tombstone push" 200 "$LAST_STATUS"
expect_json "tombstone push" "$LAST_BODY" results.0.status ACCEPTED

request GET "/api/v1/sync/pull?after=$CURSOR&limit=100" - device-b
expect_status "device B update and tombstone pull" 200 "$LAST_STATUS"
json_has_operations "$LAST_BODY" UPDATE DELETE || fail "device B pull did not contain both UPDATE and DELETE"
record "device B update and tombstone pull operations=UPDATE,DELETE"

write_body app-b <<EOF
{"appCode":"$APP_B","displayName":"Gate 2 Synthetic B","versionMetadata":"1.0.0","permittedDatasets":"[]","snapshotPolicy":"{}"}
EOF
request POST /api/v1/admin/applications "$BODY_FILE" admin
expect_status "application B creation" 201 "$LAST_STATUS"
APP_B_ID="$(json_value "$LAST_BODY" appId)" || fail "application B id missing"
write_body tenant-b <<EOF
{"appId":"$APP_B_ID","tenantCode":"$TENANT_B","tenantName":"Gate 2 Synthetic Tenant B"}
EOF
request POST /api/v1/admin/tenants "$BODY_FILE" admin
expect_status "tenant B creation" 201 "$LAST_STATUS"
TENANT_B_ID="$(json_value "$LAST_BODY" tenantId)" || fail "tenant B id missing"
request POST "/api/v1/admin/tenants/$TENANT_B_ID/enrollment-codes" - admin
expect_status "tenant B enrollment code" 201 "$LAST_STATUS"
ENROLL_C="$(json_value "$LAST_BODY" enrollmentCode)" || fail "tenant B enrollment code missing"
APP_B_LOWER="$(printf '%s' "$APP_B" | tr '[:upper:]' '[:lower:]')"
write_body device-c <<EOF
{"enrollmentCode":"$ENROLL_C","deviceName":"Gate 2 Synthetic Device C","applicationId":"com.gdb.$APP_B_LOWER","applicationVersion":"1.0.0","platform":"ANDROID"}
EOF
request POST /api/v1/devices/register "$BODY_FILE" none
expect_status "device C registration" 201 "$LAST_STATUS"
DEVICE_C_ID="$(json_value "$LAST_BODY" deviceId)" || fail "device C id missing"
DEVICE_C_TOKEN="$(json_value "$LAST_BODY" deviceToken)" || fail "device C token missing"

request GET "/api/v1/sync/pull?after=0&limit=100" - device-c
expect_status "isolated tenant pull" 200 "$LAST_STATUS"
expect_json "isolated tenant pull" "$LAST_BODY" events '[]'

FORGED_BATCH="$(new_uuid)"
write_body forged <<EOF
{"contractVersion":1,"appCode":"$APP_A","tenantId":"$TENANT_B_ID","deviceId":"$DEVICE_A_ID","batchId":"$FORGED_BATCH","events":[$EVENT_CREATE]}
EOF
request POST /api/v1/sync/push "$BODY_FILE" device-a
expect_status "cross-tenant forged push rejection" 403 "$LAST_STATUS"

SNAPSHOT_CONTENT='{"formatVersion":1,"records":[{"id":"gate2-synthetic-001"}]}'
SNAPSHOT_SHA="$(printf '%s' "$SNAPSHOT_CONTENT" | sha256sum | awk '{print $1}')"
SNAPSHOT_B64="$(printf '%s' "$SNAPSHOT_CONTENT" | base64 | tr -d '\n')"
SNAPSHOT_LENGTH="$(printf '%s' "$SNAPSHOT_CONTENT" | wc -c | tr -d ' ')"
write_body snapshot <<EOF
{"formatVersion":1,"createdAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","sha256":"$SNAPSHOT_SHA","contentLength":$SNAPSHOT_LENGTH,"payload":"$SNAPSHOT_B64","recordCounts":"{\"synthetic\":1}"}
EOF
request POST /api/v1/snapshots "$BODY_FILE" device-a
expect_status "snapshot upload" 201 "$LAST_STATUS"
SNAPSHOT_ID="$(json_value "$LAST_BODY" snapshotId)" || fail "snapshot id missing"
expect_json "snapshot upload" "$LAST_BODY" sha256 "$SNAPSHOT_SHA"

request GET "/api/v1/snapshots/$SNAPSHOT_ID" - device-c
expect_status "cross-tenant snapshot rejection" 404 "$LAST_STATUS"
request GET "/api/v1/snapshots/$SNAPSHOT_ID" - device-a
expect_status "snapshot download" 200 "$LAST_STATUS"
DOWNLOADED_SHA="$(sha256sum "$LAST_BODY" | awk '{print $1}')"
[ "$DOWNLOADED_SHA" = "$SNAPSHOT_SHA" ] || fail "downloaded snapshot checksum mismatch"
record "snapshot checksum verification sha256=$DOWNLOADED_SHA"

write_body bad-snapshot <<EOF
{"formatVersion":1,"createdAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","sha256":"$(printf '0%.0s' {1..64})","contentLength":$SNAPSHOT_LENGTH,"payload":"$SNAPSHOT_B64","recordCounts":"{\"synthetic\":1}"}
EOF
request POST /api/v1/snapshots "$BODY_FILE" device-a
expect_status "bad snapshot checksum rejection" 400 "$LAST_STATUS"

request POST "/api/v1/admin/devices/$DEVICE_B_ID/rotate" - admin
expect_status "device B credential rotation" 200 "$LAST_STATUS"
DEVICE_B_ROTATED_TOKEN="$(json_value "$LAST_BODY" deviceToken)" || fail "rotated device B token missing"
request GET /api/v1/sync/status - device-b
expect_status "old device B credential rejection" 401 "$LAST_STATUS"
request GET /api/v1/sync/status - device-b-rotated
expect_status "rotated device B credential" 200 "$LAST_STATUS"
request POST "/api/v1/admin/devices/$DEVICE_B_ID/revoke" - admin
expect_status "device B revocation" 200 "$LAST_STATUS"
request GET /api/v1/sync/status - device-b-rotated
expect_status "revoked device B credential rejection" 401 "$LAST_STATUS"

record "GATE2 PASS: loopback staging functional acceptance completed with synthetic data"
record "GATE2 scope: admin auth, app/tenant/device lifecycle, one-use enrollment, sync status, cursors, duplicate, conflict, tombstone, isolation, snapshot checksum/scope, rotation, revocation"
