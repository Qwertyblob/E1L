#!/usr/bin/env bash
#
# Idempotently install the operations crontab on the host (run once per server, and
# re-run after moving the repo — it rewrites its own block in place):
#
#   03:15 daily   backup-to-r2.sh          dump -> R2
#   03:45 monthly restore-from-r2.sh       non-destructive restore drill (1st of month)
#   every 30 min  check-mail-failures.sh   alert if the app logged mail-delivery failures
#
# Cron output lands in /var/log/e1l-*.log; set MAILTO in the crontab (or a Cloudflare
# notification on the logs) if you want failures pushed instead of pulled.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARKER_BEGIN="# >>> every1luvs ops (managed by setup-backup-cron.sh) >>>"
MARKER_END="# <<< every1luvs ops <<<"

chmod +x "$REPO_ROOT/scripts/backup-to-r2.sh" \
         "$REPO_ROOT/scripts/restore-from-r2.sh" \
         "$REPO_ROOT/scripts/check-mail-failures.sh"

BLOCK="$(cat <<EOF
$MARKER_BEGIN
15 3 * * *   $REPO_ROOT/scripts/backup-to-r2.sh        >> /var/log/e1l-backup.log 2>&1
45 3 1 * *   $REPO_ROOT/scripts/restore-from-r2.sh     >> /var/log/e1l-restore-drill.log 2>&1
*/30 * * * * $REPO_ROOT/scripts/check-mail-failures.sh >> /var/log/e1l-mail-alert.log 2>&1
$MARKER_END
EOF
)"

# Replace any previous managed block, then append the fresh one.
CURRENT="$(crontab -l 2>/dev/null || true)"
CLEANED="$(printf '%s\n' "$CURRENT" | sed "/^$(printf '%s' "$MARKER_BEGIN" | sed 's/[>/]/\\&/g')\$/,/^$(printf '%s' "$MARKER_END" | sed 's/[</]/\\&/g')\$/d")"
printf '%s\n%s\n' "$CLEANED" "$BLOCK" | crontab -

echo "Installed crontab block:"
echo "$BLOCK"
