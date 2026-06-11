#!/usr/bin/env bash
#
# Cron watchdog for swallowed @Async failures (mail sends — OTPs, reset codes, booking
# confirmations). The app logs them with the ASYNC_TASK_FAILURE marker (see AsyncConfig);
# this script greps the recent app container logs and:
#   - exits 0 silently when clean (no cron noise),
#   - prints the failures and exits 1 when found (cron MAILTO / log monitoring picks it up),
#   - optionally POSTs a summary to $ALERT_WEBHOOK_URL (Slack/Discord-style JSON) if set.
#
# Installed every 30 min by setup-backup-cron.sh; --since=35m overlaps runs so nothing
# slips between invocations (duplicate alerts are preferable to missed ones).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MARKER="ASYNC_TASK_FAILURE"

FAILURES="$(docker compose logs --no-color --since=35m app 2>/dev/null | grep "$MARKER" || true)"

[[ -z "$FAILURES" ]] && exit 0

COUNT="$(printf '%s\n' "$FAILURES" | wc -l | tr -d ' ')"
echo "[$(date -u +%FT%TZ)] ALERT: ${COUNT} async/mail delivery failure(s) in the last 35m:"
printf '%s\n' "$FAILURES"

if [[ -n "${ALERT_WEBHOOK_URL:-}" ]]; then
  curl -fsS -m 10 -X POST -H 'Content-Type: application/json' \
    -d "{\"text\":\"every1luvs: ${COUNT} mail delivery failure(s) in the last 35m — check docker compose logs app | grep ${MARKER}\"}" \
    "$ALERT_WEBHOOK_URL" > /dev/null || echo "WARN: webhook notification failed" >&2
fi

exit 1
