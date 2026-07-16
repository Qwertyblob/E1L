#!/usr/bin/env bash
#
# Idempotently install the operations crontab on the host (run once per server, and
# re-run after moving the repo — it rewrites its own block in place):
#
#   03:15 daily   backup-to-r2.sh          dump -> R2
#   03:45 monthly restore-from-r2.sh       non-destructive restore drill (1st of month)
#   every 30 min  check-mail-failures.sh   alert if the app logged mail-delivery failures
#   every 5 min   log-monitor.sh           detect prod errors -> dispatch the AI diagnoser
#
# Cron output lands in $XDG_STATE_HOME/every1luvs/logs (default ~/.local/state/every1luvs/logs) —
# a deploy-user-owned dir, verified writable at install (redirecting to /var/log needs root and
# would make the cron line fail before the script runs). Before scheduling, each job is dry-run
# with its non-mutating --check flag so a missing tool/env or bad PATH fails HERE, not at 03:15.
# Each job also emits one unredirected line on FAILURE so cron's MAILTO mails it (cron mails
# captured output, not exit codes). Set MAILTO in the crontab to receive those, or watch the logs.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARKER_BEGIN="# >>> every1luvs ops (managed by setup-backup-cron.sh) >>>"
MARKER_END="# <<< every1luvs ops <<<"

chmod +x "$REPO_ROOT/scripts/backup-to-r2.sh" \
         "$REPO_ROOT/scripts/restore-from-r2.sh" \
         "$REPO_ROOT/scripts/check-mail-failures.sh" \
         "$REPO_ROOT/scripts/log-monitor.sh"

# Deploy-user-owned log dir (NOT /var/log, which needs root to create/own — a cron line that can't
# open its redirect target fails before the script runs). Confirm it's writable before scheduling.
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/every1luvs/logs"
mkdir -p "$LOG_DIR"
if ! ( : > "$LOG_DIR/.write-test" ) 2>/dev/null; then
  echo "ERROR: cannot write to log dir $LOG_DIR" >&2
  exit 1
fi
rm -f "$LOG_DIR/.write-test"

# Cron runs with a minimal PATH; resolve the tools now and bake a PATH covering their dirs plus the
# standard locations, so the scheduled scripts find docker/rclone/python3/git.
CRON_PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
for cmd in docker rclone python3 git; do
  p="$(command -v "$cmd" 2>/dev/null || true)"
  [[ -n "$p" ]] && CRON_PATH="$(dirname "$p"):$CRON_PATH"
done

# Install-time smoke test: run each job's non-mutating --check under the SAME PATH cron will use, so
# a missing tool/env or bad PATH surfaces now instead of silently at 03:15. None of these back up,
# restore, alert or dispatch. Abort the install if any fails.
echo "Preflight (--check) on each scheduled script:"
preflight_failed=0
for s in backup-to-r2 restore-from-r2 check-mail-failures log-monitor; do
  if ! PATH="$CRON_PATH" "$REPO_ROOT/scripts/$s.sh" --check; then
    echo "  PREFLIGHT FAILED: $s.sh --check" >&2
    preflight_failed=1
  fi
done
[[ $preflight_failed -eq 0 ]] || { echo "Aborting install — fix the failures above and re-run." >&2; exit 1; }

BLOCK="$(cat <<EOF
$MARKER_BEGIN
SHELL=/bin/bash
PATH=$CRON_PATH
# Uncomment and set your address to receive the failure notices below — cron mails a job's captured
# output, so set MAILTO or you get nothing (a Cloudflare log notification is the pull alternative):
# MAILTO=you@example.com
# Each job logs full output to its file; on FAILURE it ALSO emits one unredirected line so cron's
# MAILTO delivers it. cron mails captured output, not exit codes, so a fully-redirected job would
# otherwise fail silently — that is exactly why the mail-failure watchdog needs the "|| echo".
15 3 * * *   $REPO_ROOT/scripts/backup-to-r2.sh        >> $LOG_DIR/backup.log 2>&1        || echo "e1l cron: backup-to-r2 FAILED (exit \$?) — see $LOG_DIR/backup.log"
45 3 1 * *   $REPO_ROOT/scripts/restore-from-r2.sh     >> $LOG_DIR/restore-drill.log 2>&1 || echo "e1l cron: restore-drill FAILED (exit \$?) — see $LOG_DIR/restore-drill.log"
*/30 * * * * $REPO_ROOT/scripts/check-mail-failures.sh >> $LOG_DIR/mail-alert.log 2>&1    || echo "e1l cron: mail-failure watchdog tripped (exit \$?) — see $LOG_DIR/mail-alert.log"
*/5 * * * *  $REPO_ROOT/scripts/log-monitor.sh         >> $LOG_DIR/log-monitor.log 2>&1   || echo "e1l cron: log-monitor dispatch FAILED (exit \$?) — see $LOG_DIR/log-monitor.log"
$MARKER_END
EOF
)"

# Replace any previous managed block, then append the fresh one.
CURRENT="$(crontab -l 2>/dev/null || true)"
CLEANED="$(printf '%s\n' "$CURRENT" | sed "/^$(printf '%s' "$MARKER_BEGIN" | sed 's/[>/]/\\&/g')\$/,/^$(printf '%s' "$MARKER_END" | sed 's/[</]/\\&/g')\$/d")"
printf '%s\n%s\n' "$CLEANED" "$BLOCK" | crontab -

echo "Installed crontab block:"
echo "$BLOCK"
