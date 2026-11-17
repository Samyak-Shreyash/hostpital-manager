#!/usr/bin/env bash
# Restores a dump from the db-backup service into the running dev database (replaces existing objects).
# Usage (repo root): infra/backup/restore.sh backups/hms-<timestamp>.dump
set -euo pipefail
cd "$(dirname "$0")/../.."
dump=${1:?usage: infra/backup/restore.sh <dump file>}
[ -f "$dump" ] || { echo "No such file: $dump" >&2; exit 1; }
compose=(docker compose -f infra/compose/compose.yml --env-file .env)
"${compose[@]}" stop api
"${compose[@]}" exec -T db sh -c 'pg_restore --clean --if-exists --no-owner --role=hms_owner -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$dump"
"${compose[@]}" start api
echo "Restored $dump"
