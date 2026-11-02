#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Read-only verification after the guarded synthetic-admin recovery.

CONTAINER="gdb-staging-postgres"
PGPASSFILE="/tmp/gdb-gate2-post-recovery.pgpass"

cleanup() {
  sudo docker exec "$CONTAINER" rm -f "$PGPASSFILE" >/dev/null 2>&1 || true
  unset password
}
trap cleanup EXIT

for command_name in sudo docker awk curl; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "POST-RECOVERY STOP: missing command $command_name" >&2
    exit 1
  }
done

sudo -v

metadata="$(sudo docker inspect "$CONTAINER" \
  --format '{{.Name}}|{{.Config.Image}}|{{.State.Status}}|{{.State.Health.Status}}')"
[ "$metadata" = "/gdb-staging-postgres|postgres:18.6-alpine|running|healthy" ] || {
  echo "POST-RECOVERY STOP: staging PostgreSQL identity/health mismatch" >&2
  exit 1
}

health_code="$(curl --silent --show-error --max-time 5 \
  --output /dev/null --write-out '%{http_code}' \
  http://127.0.0.1:8192/actuator/health)"
[ "$health_code" = "200" ] || {
  echo "POST-RECOVERY STOP: backend health HTTP $health_code" >&2
  exit 1
}

password="$(sudo awk -F= \
  '$1 == "GDB_MIGRATOR_PASSWORD" {sub(/^[^=]*=/, ""); print; exit}' \
  /etc/gdb-staging/roles.env)"
[ -n "$password" ] || {
  echo "POST-RECOVERY STOP: migration credential is unavailable" >&2
  exit 1
}

printf '*:*:gdb_staging:gdb_staging_migrator:%s\n' "$password" |
  sudo docker exec -i "$CONTAINER" \
  sh -c "umask 077; cat > $PGPASSFILE"
unset password

echo "GATE2: post-recovery read-only verification"
echo "backend_health_http=$health_code"

sudo docker exec -i -e PGPASSFILE="$PGPASSFILE" "$CONTAINER" \
  psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 \
  --tuples-only --no-align --field-separator='|' \
  -h 127.0.0.1 \
  -U gdb_staging_migrator \
  -d gdb_staging <<'SQL'
SELECT
  'identity' AS check_name,
  current_database() AS database_name,
  current_user AS database_role;

SELECT
  'admin_recovery_state' AS check_name,
  (SELECT count(*) FROM gdb_core.admin_users) AS admin_users,
  (SELECT count(*) FROM gdb_core.admin_sessions) AS admin_sessions;

SELECT
  'application_data_baseline' AS check_name,
  (SELECT count(*) FROM gdb_core.applications) AS applications,
  (SELECT count(*) FROM gdb_core.tenants) AS tenants,
  (SELECT count(*) FROM gdb_core.devices) AS devices,
  (SELECT count(*) FROM gdb_core.sync_batches) AS sync_batches,
  (SELECT count(*) FROM gdb_core.sync_event_results) AS sync_event_results,
  (SELECT count(*) FROM gdb_core.sync_sessions) AS sync_sessions,
  (SELECT count(*) FROM gdb_core.snapshots) AS snapshots;

SELECT
  'audit_baseline' AS check_name,
  count(*) AS audit_rows,
  count(*) FILTER (WHERE actor_type = 'ADMIN') AS admin_audit_rows,
  count(*) FILTER (WHERE actor_id = 'gate2admin') AS target_admin_audit_rows
FROM gdb_core.audit_logs;

SELECT format(
  'SELECT %L AS application_schema,
          (SELECT count(*) FROM %I.sync_event_log) AS event_rows,
          (SELECT count(*) FROM %I.entity_state
             WHERE tombstone = true) AS tombstone_rows;',
  schema_name,
  schema_name,
  schema_name
)
FROM gdb_core.applications
\gexec
SQL

echo "GATE2: post-recovery verification complete; Gate 2 harness not executed"
