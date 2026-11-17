#!/usr/bin/env bash
# Starts the dev stack, reusing the persistent database volume if it exists (created only on first run).
# Postgres runs the db-init scripts only on an empty volume, so an existing database is never re-initialised.
# Usage (repo root): infra/compose/up.sh [extra compose up args, e.g. --profile monitoring]
set -euo pipefail
cd "$(dirname "$0")/../.."
vol=$(grep -E '^HMS_DB_VOLUME=' .env 2>/dev/null | cut -d= -f2- || true)
vol=${vol:-hms-db-data}
if docker volume inspect "$vol" >/dev/null 2>&1; then
  echo "Reusing database volume '$vol'"
else
  echo "Database volume '$vol' not found; creating it (fresh database)"
  docker volume create "$vol" >/dev/null
fi
exec docker compose -f infra/compose/compose.yml --env-file .env up -d --build --wait "$@"
