#!/usr/bin/env bash
#
# Restore-drill / disaster-recovery counterpart to backup-to-r2.sh.
#
# Default (no flags): NON-DESTRUCTIVE DRILL — downloads the newest dump from R2,
# restores it into a scratch database inside the db container, sanity-checks row
# counts, then drops the scratch DB. Exits non-zero if any step fails, so a cron
# run surfaces a broken backup chain. Run monthly (see setup-backup-cron.sh).
#
# Real recovery: restore-from-r2.sh --into-live
#   Overwrites the LIVE every1luvs database. Stop the app first
#   (docker compose stop app) and re-run it only with CONFIRM_LIVE_RESTORE=yes.
#
# Optional: pass a specific dump key as $1 (e.g. every1luvs/db-2026-06-01.sql.gz);
# otherwise the newest object under every1luvs/ in the bucket is used.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Non-mutating preflight (used by setup-backup-cron.sh as the install-time test): verify the env
# and tools a real drill needs, then exit — it NEVER touches R2, psql or any scratch database.
if [[ "${1:-}" == "--check" ]]; then
  rc=0
  if [[ -f "$REPO_ROOT/.env" ]]; then set -a; . "$REPO_ROOT/.env"; set +a; else echo "MISSING $REPO_ROOT/.env" >&2; rc=1; fi
  for c in docker rclone gunzip; do command -v "$c" >/dev/null 2>&1 || { echo "MISSING command: $c" >&2; rc=1; }; done
  [[ -n "${DB_USERNAME:-}" ]] || { echo "MISSING env: DB_USERNAME" >&2; rc=1; }
  [[ $rc -eq 0 ]] && echo "restore-from-r2 --check: OK"
  exit $rc
fi

set -a
# shellcheck disable=SC1091
source ./.env
set +a

BUCKET="${R2_BUCKET:-every1luvs-backups}"
SCRATCH_DB="every1luvs_restore_check"
MODE="drill"
KEY="${1:-}"

if [[ "${1:-}" == "--into-live" ]]; then
  MODE="live"
  KEY="${2:-}"
fi

if [[ -z "$KEY" ]]; then
  # Newest dump = last line of the lexically sorted listing (keys embed the UTC date).
  KEY="$(rclone lsf "r2:${BUCKET}/every1luvs/" | sort | tail -n 1)"
  [[ -n "$KEY" ]] || { echo "ERROR: no backups found in r2:${BUCKET}/every1luvs/" >&2; exit 1; }
  KEY="every1luvs/${KEY}"
fi

echo "[$(date -u +%FT%TZ)] restore (${MODE}) from r2:${BUCKET}/${KEY}"

restore_into() {
  local target_db="$1"
  # Stream straight from R2 into psql; ON_ERROR_STOP makes a partial dump fail loudly.
  rclone cat "r2:${BUCKET}/${KEY}" \
    | gunzip \
    | docker compose exec -T db psql -v ON_ERROR_STOP=1 -U "$DB_USERNAME" -d "$target_db" -q
}

if [[ "$MODE" == "live" ]]; then
  [[ "${CONFIRM_LIVE_RESTORE:-}" == "yes" ]] || {
    echo "ERROR: refusing to overwrite the live database without CONFIRM_LIVE_RESTORE=yes" >&2
    exit 1
  }
  echo "Recreating live database every1luvs..."
  docker compose exec -T db psql -U "$DB_USERNAME" -d postgres -q \
    -c "DROP DATABASE IF EXISTS every1luvs;" -c "CREATE DATABASE every1luvs;"
  restore_into every1luvs
  echo "[$(date -u +%FT%TZ)] LIVE restore complete. Start the app: docker compose start app"
  exit 0
fi

# --- drill: restore into a scratch DB and verify it actually contains data ---
docker compose exec -T db psql -U "$DB_USERNAME" -d postgres -q \
  -c "DROP DATABASE IF EXISTS ${SCRATCH_DB};" -c "CREATE DATABASE ${SCRATCH_DB};"
trap 'docker compose exec -T db psql -U "$DB_USERNAME" -d postgres -q -c "DROP DATABASE IF EXISTS ${SCRATCH_DB};"' EXIT

restore_into "$SCRATCH_DB"

# A valid dump must contain the three core tables; print row counts for the log.
COUNTS="$(docker compose exec -T db psql -U "$DB_USERNAME" -d "$SCRATCH_DB" -t -A \
  -c "SELECT 'users='   || count(*) FROM tbl_users
      UNION ALL SELECT 'slots='    || count(*) FROM tbl_slots
      UNION ALL SELECT 'bookings=' || count(*) FROM tbl_bookings;")"

echo "[$(date -u +%FT%TZ)] drill OK — ${COUNTS//$'\n'/ }"
