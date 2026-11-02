#!/usr/bin/env bash
set -euo pipefail

# Approval-gated example. Run only on the intended GDB host after reviewing
# paths, retention, encryption-key custody, and the restore procedure.
# The output must go to independent storage, not only the GDB server.
backup_dir="${1:?usage: backup-postgres.example.sh /independent/backup/path}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup_dir"
dump="$backup_dir/gdb-$timestamp.dump"
pg_dump --format=custom --file="$dump" "${GDB_DB_URL:?set a psql-compatible database target}"
sha256sum "$dump" > "$dump.sha256"
echo "Database dump and checksum created at $backup_dir"
echo "Encrypt the dump with the approved off-server key envelope before leaving the host."
echo "Copy /var/lib/gdb/snapshots with per-object checksums and verify both artifacts after transfer."
