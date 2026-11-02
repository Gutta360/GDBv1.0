#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
test -f "$repo_dir/backend/pom.xml"
test -f "$repo_dir/backend/src/main/resources/db/migration/V1__create_gdb_core.sql"
test -f "$repo_dir/frontend/package.json"
test -f "$repo_dir/client/typescript/src/index.ts"
test -f "$repo_dir/docs/api/API_CONTRACT.md"
if rg -n 'GT2_ADMIN_TOKEN|GT2_DB_PASSWORD|POSTGRES_PASSWORD=|GDB_ADMIN_TOKEN=replace' "$repo_dir" --glob '!*.example' --glob '!target/**' --glob '!node_modules/**' --glob '!scripts/validate-repository.sh'; then
  echo "Potential committed secret or GT2 runtime reference found" >&2
  exit 1
fi
echo "GDB repository structure and secret scan passed"
