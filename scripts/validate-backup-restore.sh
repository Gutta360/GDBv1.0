#!/usr/bin/env bash
set -euo pipefail

# Nonproduction validation only. This starts one exact, disposable PostgreSQL
# container and writes artifacts to an explicit destination. It does not touch
# any production database or existing container.
#
# The CLI encryption step uses OpenSSL AES-256-CBC for portability in this local
# harness. Production GDB backups must use an owner-approved AEAD/KMS or
# equivalent envelope and must keep the key outside the backup destination.

image="${GDB_TEST_POSTGRES_IMAGE:-postgres:18-alpine}"
destination="${1:?usage: validate-backup-restore.sh /path/to/separate-test-destination}"
container="gdb-backup-validation-${PPID}-${RANDOM}"
postgres_password_env="POSTGRES_PASSWORD"
work="$(mktemp -d "${TMPDIR:-/tmp}/gdb-backup-validation.XXXXXX")"
key_file="$work/backup.key"
dump_file="$work/gdb.dump"
restore_dump="$work/gdb.restore.dump"
snapshot_plain="$work/snapshot.json"
snapshot_restore="$work/snapshot.restore.json"
pass_file="$work/postgres-password"

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

mkdir -p "$destination"
umask 077
printf 'local-validation-postgres-password-%s' "$RANDOM" > "$pass_file"
openssl rand -base64 32 > "$key_file"

docker run -d --name "$container" \
  -e "${postgres_password_env}=$(<"$pass_file")" \
  -e POSTGRES_DB=gdb \
  "$image" >/dev/null

for attempt in $(seq 1 60); do
  if docker exec "$container" pg_isready -U postgres -d gdb >/dev/null 2>&1; then break; fi
  if [[ "$attempt" == 60 ]]; then echo "PostgreSQL did not become ready" >&2; exit 1; fi
  sleep 1
done

docker exec "$container" psql -v ON_ERROR_STOP=1 -U postgres -d gdb -c \
  "CREATE TABLE backup_validation_business_data (id INTEGER PRIMARY KEY, value TEXT NOT NULL); INSERT INTO backup_validation_business_data VALUES (1, 'alpha'), (2, 'beta');" >/dev/null

docker exec "$container" pg_dump --format=custom --no-owner --no-privileges -U postgres -d gdb > "$dump_file"
openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt -in "$dump_file" -out "$destination/gdb.dump.enc" -pass file:"$key_file"

printf '%s\n' '{"formatVersion":1,"records":[{"id":1,"value":"alpha"},{"id":2,"value":"beta"}]}' > "$snapshot_plain"
openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt -in "$snapshot_plain" -out "$destination/snapshot.json.enc" -pass file:"$key_file"

sha256sum "$destination/gdb.dump.enc" "$destination/snapshot.json.enc" > "$destination/SHA256SUMS"
sha256sum --check "$destination/SHA256SUMS" >/dev/null

openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -in "$destination/gdb.dump.enc" -out "$restore_dump" -pass file:"$key_file"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -in "$destination/snapshot.json.enc" -out "$snapshot_restore" -pass file:"$key_file"
cmp "$snapshot_plain" "$snapshot_restore"

docker exec "$container" createdb -U postgres gdb_restore
docker exec -i "$container" pg_restore --exit-on-error --no-owner --no-privileges -U postgres -d gdb_restore < "$restore_dump"
restored_count="$(docker exec "$container" psql -At -U postgres -d gdb_restore -c 'SELECT count(*) FROM backup_validation_business_data;')"
[[ "$restored_count" == "2" ]]

printf 'Backup validation PASS\n'
printf 'PostgreSQL image: %s\n' "$image"
printf 'Encrypted artifacts: %s\n' "$destination/gdb.dump.enc $destination/snapshot.json.enc"
printf 'Checksum manifest: %s\n' "$destination/SHA256SUMS"
printf 'Restore rows verified: %s\n' "$restored_count"
printf 'Destination bytes: %s\n' "$(du -sk "$destination" | awk '{print $1 * 1024}')"
