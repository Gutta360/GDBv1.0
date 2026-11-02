#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# One-time, narrowly scoped Gate 2 synthetic-admin recovery.
# This script is intentionally not idempotent and must be run only with
# --execute after separate owner authorization.

if [ "${1:-}" != "--execute" ]; then
  echo "Usage: $0 --execute" >&2
  exit 2
fi

CONTAINER="gdb-staging-postgres"
PGPASSFILE="/tmp/gdb-gate2-admin-recovery.pgpass"
LOCKFILE="/tmp/gdb-gate2-admin-recovery.lock"
LOCK_HELD=false

cleanup() {
  sudo docker exec "$CONTAINER" rm -f "$PGPASSFILE" >/dev/null 2>&1 || true
  if [ "$LOCK_HELD" = true ]; then
    rm -f "$LOCKFILE"
  fi
  unset password confirmation
}
trap cleanup EXIT

for command_name in sudo docker awk flock; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "GATE2 RECOVERY STOP: missing command $command_name" >&2
    exit 1
  }
done

sudo -v

metadata="$(sudo docker inspect "$CONTAINER" \
  --format '{{.Name}}|{{.Config.Image}}|{{.State.Status}}|{{.State.Health.Status}}')"
[ "$metadata" = "/gdb-staging-postgres|postgres:18.6-alpine|running|healthy" ] || {
  echo "GATE2 RECOVERY STOP: staging PostgreSQL identity/health mismatch" >&2
  exit 1
}

[ -e "$LOCKFILE" ] || : > "$LOCKFILE"
exec 9>"$LOCKFILE"
if ! flock -n 9; then
  echo "GATE2 RECOVERY STOP: another Gate 2 recovery is active" >&2
  exit 1
fi
LOCK_HELD=true

printf 'Type GATE2-RECOVERY to confirm no Gate 2 test or administrator provisioning is running: '
read -r confirmation
[ "$confirmation" = "GATE2-RECOVERY" ] || {
  echo "GATE2 RECOVERY STOP: concurrency confirmation not received" >&2
  exit 1
}

password="$(sudo awk -F= \
  '$1 == "GDB_MIGRATOR_PASSWORD" {sub(/^[^=]*=/, ""); print; exit}' \
  /etc/gdb-staging/roles.env)"
[ -n "$password" ] || {
  echo "GATE2 RECOVERY STOP: migration credential is unavailable" >&2
  exit 1
}

printf '*:*:gdb_staging:gdb_staging_migrator:%s\n' "$password" |
  sudo docker exec -i "$CONTAINER" \
  sh -c "umask 077; cat > $PGPASSFILE"
unset password

echo "GATE2: executing guarded synthetic-admin recovery transaction"

sudo docker exec -i -e PGPASSFILE="$PGPASSFILE" "$CONTAINER" \
  psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 \
  -h 127.0.0.1 \
  -U gdb_staging_migrator \
  -d gdb_staging <<'SQL'
BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
DECLARE
  target_id uuid;
  total_users bigint;
  target_users bigint;
  other_users bigint;
  target_sessions bigint;
  other_sessions bigint;
  app_rows bigint;
  tenant_rows bigint;
  device_rows bigint;
  batch_rows bigint;
  result_rows bigint;
  sync_session_rows bigint;
  snapshot_rows bigint;
  event_rows bigint;
  tombstone_rows bigint;
  audit_rows bigint;
  admin_audit_rows bigint;
  target_audit_rows bigint;
BEGIN
  IF current_database() <> 'gdb_staging' THEN
    RAISE EXCEPTION 'wrong database: %', current_database();
  END IF;

  IF current_user <> 'gdb_staging_migrator' THEN
    RAISE EXCEPTION 'wrong database role: %', current_user;
  END IF;

  PERFORM 1 FROM gdb_core.admin_users FOR UPDATE;
  PERFORM 1 FROM gdb_core.admin_sessions FOR UPDATE;

  SELECT count(*),
         count(*) FILTER (WHERE username = 'gate2admin' AND status = 'ACTIVE'),
         count(*) FILTER (WHERE username <> 'gate2admin' OR status <> 'ACTIVE')
  INTO total_users, target_users, other_users
  FROM gdb_core.admin_users;

  IF total_users <> 1 OR target_users <> 1 OR other_users <> 0 THEN
    RAISE EXCEPTION 'administrator scope changed';
  END IF;

  SELECT admin_user_id
  INTO target_id
  FROM gdb_core.admin_users
  WHERE username = 'gate2admin' AND status = 'ACTIVE';

  SELECT count(*) INTO target_sessions
  FROM gdb_core.admin_sessions
  WHERE admin_user_id = target_id;

  SELECT count(*) INTO other_sessions
  FROM gdb_core.admin_sessions
  WHERE admin_user_id IS DISTINCT FROM target_id;

  IF target_sessions <> 2 OR other_sessions <> 0 THEN
    RAISE EXCEPTION 'session scope changed';
  END IF;

  SELECT count(*) INTO app_rows FROM gdb_core.applications;
  SELECT count(*) INTO tenant_rows FROM gdb_core.tenants;
  SELECT count(*) INTO device_rows FROM gdb_core.devices;
  SELECT count(*) INTO batch_rows FROM gdb_core.sync_batches;
  SELECT count(*) INTO result_rows FROM gdb_core.sync_event_results;
  SELECT count(*) INTO sync_session_rows FROM gdb_core.sync_sessions;
  SELECT count(*) INTO snapshot_rows FROM gdb_core.snapshots;

  EXECUTE 'SELECT count(*) FROM gdb_app_g2a20261102122611.sync_event_log'
    INTO event_rows;
  EXECUTE 'SELECT count(*) FROM gdb_app_g2a20261102122611.entity_state WHERE tombstone = true'
    INTO tombstone_rows;

  SELECT count(*),
         count(*) FILTER (WHERE actor_type = 'ADMIN'),
         count(*) FILTER (WHERE actor_id = 'gate2admin')
  INTO audit_rows, admin_audit_rows, target_audit_rows
  FROM gdb_core.audit_logs;

  IF app_rows <> 1 OR tenant_rows <> 1 OR device_rows <> 2
     OR batch_rows <> 3 OR result_rows <> 3 OR sync_session_rows <> 3
     OR snapshot_rows <> 0 OR event_rows <> 2 OR tombstone_rows <> 0
     OR audit_rows <> 7 OR admin_audit_rows <> 5
     OR target_audit_rows <> 3 THEN
    RAISE EXCEPTION 'application or audit baseline changed';
  END IF;
END $$;

DO $$
DECLARE
  target_id uuid;
  deleted_sessions integer;
  deleted_users integer;
BEGIN
  SELECT admin_user_id
  INTO target_id
  FROM gdb_core.admin_users
  WHERE username = 'gate2admin' AND status = 'ACTIVE';

  DELETE FROM gdb_core.admin_sessions
  WHERE admin_user_id = target_id;
  GET DIAGNOSTICS deleted_sessions = ROW_COUNT;

  IF deleted_sessions <> 2 THEN
    RAISE EXCEPTION 'expected 2 session deletions, got %', deleted_sessions;
  END IF;

  DELETE FROM gdb_core.admin_users
  WHERE admin_user_id = target_id
    AND username = 'gate2admin'
    AND status = 'ACTIVE';
  GET DIAGNOSTICS deleted_users = ROW_COUNT;

  IF deleted_users <> 1 THEN
    RAISE EXCEPTION 'expected 1 administrator deletion, got %', deleted_users;
  END IF;

  RAISE NOTICE 'recovery affected admin_sessions=2 admin_users=1';
END $$;

DO $$
DECLARE
  app_rows bigint;
  tenant_rows bigint;
  device_rows bigint;
  batch_rows bigint;
  result_rows bigint;
  sync_session_rows bigint;
  snapshot_rows bigint;
  event_rows bigint;
  tombstone_rows bigint;
  audit_rows bigint;
  admin_audit_rows bigint;
  target_audit_rows bigint;
BEGIN
  IF (SELECT count(*) FROM gdb_core.admin_users) <> 0
     OR (SELECT count(*) FROM gdb_core.admin_sessions) <> 0 THEN
    RAISE EXCEPTION 'bootstrap eligibility was not restored';
  END IF;

  SELECT count(*) INTO app_rows FROM gdb_core.applications;
  SELECT count(*) INTO tenant_rows FROM gdb_core.tenants;
  SELECT count(*) INTO device_rows FROM gdb_core.devices;
  SELECT count(*) INTO batch_rows FROM gdb_core.sync_batches;
  SELECT count(*) INTO result_rows FROM gdb_core.sync_event_results;
  SELECT count(*) INTO sync_session_rows FROM gdb_core.sync_sessions;
  SELECT count(*) INTO snapshot_rows FROM gdb_core.snapshots;

  EXECUTE 'SELECT count(*) FROM gdb_app_g2a20261102122611.sync_event_log'
    INTO event_rows;
  EXECUTE 'SELECT count(*) FROM gdb_app_g2a20261102122611.entity_state WHERE tombstone = true'
    INTO tombstone_rows;

  SELECT count(*),
         count(*) FILTER (WHERE actor_type = 'ADMIN'),
         count(*) FILTER (WHERE actor_id = 'gate2admin')
  INTO audit_rows, admin_audit_rows, target_audit_rows
  FROM gdb_core.audit_logs;

  IF app_rows <> 1 OR tenant_rows <> 1 OR device_rows <> 2
     OR batch_rows <> 3 OR result_rows <> 3 OR sync_session_rows <> 3
     OR snapshot_rows <> 0 OR event_rows <> 2 OR tombstone_rows <> 0
     OR audit_rows <> 7 OR admin_audit_rows <> 5
     OR target_audit_rows <> 3 THEN
    RAISE EXCEPTION 'application or audit data changed';
  END IF;

  RAISE NOTICE 'bootstrap_eligible=true application_data_preserved=true audit_preserved=true';
END $$;

COMMIT;
SQL

echo "GATE2: guarded recovery committed; baselines preserved"
