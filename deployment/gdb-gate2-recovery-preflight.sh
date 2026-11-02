#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Read-only Gate 2 recovery inspection. No DML, DDL, truncation, or commit.

CONTAINER="gdb-staging-postgres"
PGPASSFILE="/tmp/gdb-gate2-recovery-preflight.pgpass"

cleanup() {
  sudo docker exec "$CONTAINER" rm -f "$PGPASSFILE" >/dev/null 2>&1 || true
  unset password
}
trap cleanup EXIT

for command_name in sudo docker awk; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "PREFLIGHT STOP: missing command $command_name" >&2
    exit 1
  }
done

sudo -v

sudo docker inspect "$CONTAINER" \
  --format 'container={{.Name}} image={{.Config.Image}} state={{.State.Status}} health={{.State.Health.Status}}'

password="$(sudo awk -F= \
  '$1 == "GDB_MIGRATOR_PASSWORD" {sub(/^[^=]*=/, ""); print; exit}' \
  /etc/gdb-staging/roles.env)"
[ -n "$password" ] || {
  echo 'PREFLIGHT STOP: migration credential is unavailable' >&2
  exit 1
}

printf '*:*:gdb_staging:gdb_staging_migrator:%s\n' "$password" |
  sudo docker exec -i "$CONTAINER" \
  sh -c "umask 077; cat > $PGPASSFILE"
unset password

echo 'GATE2: read-only recovery preflight'

sudo docker exec -i -e PGPASSFILE="$PGPASSFILE" "$CONTAINER" \
  psql --no-psqlrc --quiet --tuples-only --no-align \
  --field-separator='|' \
  -h 127.0.0.1 \
  -U gdb_staging_migrator \
  -d gdb_staging <<'SQL'
SELECT
  'identity' AS check_name,
  current_database() AS database_name,
  current_user AS database_role,
  COALESCE(inet_server_addr()::text, 'local') AS server_address,
  inet_server_port() AS server_port;

SELECT
  'tables' AS check_name,
  to_regclass('gdb_core.admin_users') AS admin_users_table,
  to_regclass('gdb_core.admin_sessions') AS admin_sessions_table;

SELECT
  'administrator_scope' AS check_name,
  count(*) AS total_admin_users,
  count(*) FILTER (
    WHERE username = 'gate2admin' AND status = 'ACTIVE'
  ) AS intended_active_admins,
  count(*) FILTER (
    WHERE username <> 'gate2admin' OR status <> 'ACTIVE'
  ) AS unrelated_admin_rows
FROM gdb_core.admin_users;

SELECT
  'target_administrator' AS check_name,
  username,
  status,
  count(s.admin_session_id) AS associated_sessions
FROM gdb_core.admin_users u
LEFT JOIN gdb_core.admin_sessions s
  ON s.admin_user_id = u.admin_user_id
WHERE u.username = 'gate2admin'
GROUP BY username, status;

SELECT
  'session_scope' AS check_name,
  count(*) AS total_admin_sessions,
  (
    SELECT count(*)
    FROM gdb_core.admin_sessions s
    WHERE s.admin_user_id = (
      SELECT admin_user_id
      FROM gdb_core.admin_users
      WHERE username = 'gate2admin'
    )
  ) AS intended_associated_sessions,
  (
    SELECT count(*)
    FROM gdb_core.admin_sessions s
    WHERE s.admin_user_id IS DISTINCT FROM (
      SELECT admin_user_id
      FROM gdb_core.admin_users
      WHERE username = 'gate2admin'
    )
  ) AS unrelated_session_rows
FROM gdb_core.admin_sessions;

SELECT
  'foreign_keys' AS check_name,
  conname AS constraint_name,
  conrelid::regclass AS referencing_table,
  confrelid::regclass AS referenced_table,
  pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE contype = 'f'
  AND (
    confrelid IN (
      'gdb_core.admin_users'::regclass,
      'gdb_core.admin_sessions'::regclass
    )
    OR conrelid IN (
      'gdb_core.admin_users'::regclass,
      'gdb_core.admin_sessions'::regclass
    )
  )
ORDER BY conname;

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

echo 'GATE2: read-only recovery preflight complete'
