#!/usr/bin/env bash
#
# AI log-monitor — DETECTOR (push model, runs on the VPS via cron every 5 min).
#
# Greps the recent container logs for errors and, when it finds NEW ones, fires a GitHub
# `repository_dispatch` event so the log-monitor workflow can wake Claude Code to diagnose
# and open a PR (code fix) or an issue (config/infra fix). The VPS reaches OUT; nothing gets
# inbound access to the box. Mirrors scripts/check-mail-failures.sh.
#
# Three cost guards (a crash loop emits thousands of ERROR lines — without these that would be
# thousands of AI invocations):
#   - DEBOUNCE  : one POST per run; cron batches a whole ~5-min window into a single dispatch.
#   - DEDUPE    : each distinct error signature is sent at most once per LOG_MONITOR_DEDUPE_TTL.
#   - CIRCUIT   : hard cap of LOG_MONITOR_MAX_PER_HOUR dispatches/hour, no matter what.
#
# The dispatch bundles the DEPLOYED commit SHA (images are pinned to ${github.sha} on deploy),
# so the workflow checks out the exact running source. Also bundles service, count, signatures,
# a bounded log excerpt, host and timestamp.
#
# Required env (put in ~/E1L/.env alongside the rest of the stack config):
#   GITHUB_REPO          owner/name, e.g. Qwertyblob/E1L
#   LOG_MONITOR_TOKEN    fine-grained PAT scoped to ONLY this repo, Contents: read+write
#                        (that is the permission GitHub's dispatch API requires). Use a
#                        dedicated machine account so a leak is contained to this one repo.
# Optional env (sane defaults below).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Non-mutating preflight (used by setup-backup-cron.sh as the install-time test): verify the env
# and tools the detector needs, then exit — it NEVER scans logs or POSTs a dispatch.
if [[ "${1:-}" == "--check" ]]; then
  rc=0
  if [[ -f "$REPO_ROOT/.env" ]]; then set -a; . "$REPO_ROOT/.env"; set +a; else echo "MISSING $REPO_ROOT/.env" >&2; rc=1; fi
  for c in docker curl python3 git shasum awk; do command -v "$c" >/dev/null 2>&1 || { echo "MISSING command: $c" >&2; rc=1; }; done
  for v in GITHUB_REPO LOG_MONITOR_TOKEN; do [[ -n "${!v:-}" ]] || { echo "MISSING env: $v" >&2; rc=1; }; done
  [[ $rc -eq 0 ]] && echo "log-monitor --check: OK"
  exit $rc
fi

# Load .env so cron (which has a bare environment) sees the config the rest of the stack uses.
if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a; . "$REPO_ROOT/.env"; set +a
fi

GITHUB_REPO="${GITHUB_REPO:-}"
LOG_MONITOR_TOKEN="${LOG_MONITOR_TOKEN:-}"
STATE_DIR="${LOG_MONITOR_STATE_DIR:-$HOME/.e1l-log-monitor}"
WINDOW="${LOG_MONITOR_WINDOW:-6m}"              # > cron interval so nothing slips between runs
DEDUPE_TTL="${LOG_MONITOR_DEDUPE_TTL:-21600}"   # 6h: re-alert on a recurring signature at most that often
MAX_PER_HOUR="${LOG_MONITOR_MAX_PER_HOUR:-3}"   # circuit breaker
SERVICES="${LOG_MONITOR_SERVICES:-app web db}"
PATTERN="${LOG_MONITOR_PATTERN:-ERROR|FATAL|Exception}"
MAX_LINES="${LOG_MONITOR_MAX_LINES:-40}"        # cap the bundled excerpt (keeps dispatch small)

if [[ -z "$GITHUB_REPO" || -z "$LOG_MONITOR_TOKEN" ]]; then
  echo "[$(date -u +%FT%TZ)] log-monitor: GITHUB_REPO and LOG_MONITOR_TOKEN must be set (see .env). Skipping." >&2
  exit 0
fi

mkdir -p "$STATE_DIR"
SIG_FILE="$STATE_DIR/signatures"   # lines: "<epoch> <sighash>" — for dedupe
SEND_FILE="$STATE_DIR/sends"       # lines: "<epoch>"           — for the hourly circuit breaker
NOW="$(date +%s)"

# --- 1. Collect matching log lines across services ---------------------------------------
RAW="$(mktemp)"
KEPT_SIGS="$(mktemp)"
trap 'rm -f "$RAW" "$KEPT_SIGS"' EXIT
for svc in $SERVICES; do
  docker compose logs --no-color --since="$WINDOW" "$svc" 2>/dev/null \
    | grep -aE "$PATTERN" \
    | sed "s/^/${svc}| /" >> "$RAW" || true
done

[[ -s "$RAW" ]] || exit 0   # nothing matched — stay silent (no cron noise)

# --- 2. Reduce each line to a stable signature, dedupe against recent state ---------------
# Strip the leading "svc| ", ISO timestamps, and digits/hex so the same fault hashes the same
# regardless of thread id / counter / timestamp; then sha256 the normalized text.
normalize() { sed -E 's/^[a-z]+\| //; s/[0-9T:.+-]{8,}//g; s/0x[0-9a-fA-F]+//g; s/[0-9]+//g'; }

# Prune dedupe state older than the TTL, keep the survivors.
if [[ -f "$SIG_FILE" ]]; then
  awk -v now="$NOW" -v ttl="$DEDUPE_TTL" '($1+ttl) > now' "$SIG_FILE" > "$KEPT_SIGS" || true
fi

declare -A SEEN_NEW=()   # sighash -> 1, the new (non-duplicate) signatures this run
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  sig="$(printf '%s' "$line" | normalize | shasum -a 256 | cut -c1-16)"
  # duplicate if already in the (pruned) state, or already counted this run
  if grep -q " $sig\$" "$KEPT_SIGS" 2>/dev/null || [[ -n "${SEEN_NEW[$sig]:-}" ]]; then
    continue
  fi
  SEEN_NEW["$sig"]=1
done < "$RAW"

NEW_COUNT=${#SEEN_NEW[@]}
if [[ "$NEW_COUNT" -eq 0 ]]; then
  exit 0   # every error this window was already reported recently
fi

# --- 3. Circuit breaker: cap dispatches per rolling hour ----------------------------------
SENT_LAST_HOUR=0
if [[ -f "$SEND_FILE" ]]; then
  SENT_LAST_HOUR="$(awk -v now="$NOW" '($1+3600) > now' "$SEND_FILE" | wc -l | tr -d ' ')"
fi
if [[ "$SENT_LAST_HOUR" -ge "$MAX_PER_HOUR" ]]; then
  echo "[$(date -u +%FT%TZ)] log-monitor: CIRCUIT OPEN — ${SENT_LAST_HOUR} dispatch(es) in the last hour (cap ${MAX_PER_HOUR}); ${NEW_COUNT} new signature(s) held back." >&2
  exit 0
fi

# --- 4. Determine the DEPLOYED commit SHA (the running app image's tag) --------------------
COMMIT=""
APP_CID="$(docker compose ps -q app 2>/dev/null || true)"
if [[ -n "$APP_CID" ]]; then
  IMG="$(docker inspect --format '{{.Config.Image}}' "$APP_CID" 2>/dev/null || true)"
  TAG="${IMG##*:}"
  [[ "$TAG" != "latest" && -n "$TAG" && "$TAG" != "$IMG" ]] && COMMIT="$TAG"
fi
# Fallback: deploy.yml does `git pull --ff-only` to the deployed commit, so HEAD matches.
[[ -z "$COMMIT" ]] && COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"

# --- 5. Build the dispatch payload (python3 for safe JSON escaping) ------------------------
PRIMARY_SVC="$(head -n1 "$RAW" | cut -d'|' -f1)"
PAYLOAD="$(
  COMMIT="$COMMIT" \
  PRIMARY_SVC="$PRIMARY_SVC" \
  SERVICES="$SERVICES" \
  NEW_COUNT="$NEW_COUNT" \
  SIGS="$(printf '%s\n' "${!SEEN_NEW[@]}")" \
  MAX_LINES="$MAX_LINES" \
  RAW_FILE="$RAW" \
  HOST="$(hostname)" \
  python3 - <<'PY'
import json, os, datetime
now_utc = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
max_lines = int(os.environ["MAX_LINES"])
with open(os.environ["RAW_FILE"], encoding="utf-8", errors="replace") as f:
    lines = [l.rstrip("\n")[:500] for l in f]          # cap line length
excerpt = lines[:max_lines]
truncated = len(lines) - len(excerpt)
sigs = [s for s in os.environ["SIGS"].splitlines() if s]
payload = {
    "event_type": "prod-error",
    "client_payload": {
        "commit": os.environ["COMMIT"],
        "primary_service": os.environ["PRIMARY_SVC"],
        "services_scanned": os.environ["SERVICES"],
        "new_signature_count": int(os.environ["NEW_COUNT"]),
        "signatures": sigs,
        "host": os.environ["HOST"],
        "detected_at": now_utc,
        "log_excerpt": "\n".join(excerpt) + (f"\n... (+{truncated} more matched lines)" if truncated > 0 else ""),
    },
}
print(json.dumps(payload))
PY
)"

# --- 6. POST the dispatch -----------------------------------------------------------------
HTTP_CODE="$(curl -sS -m 20 -o /tmp/e1l-logmon-resp -w '%{http_code}' \
  -X POST "https://api.github.com/repos/${GITHUB_REPO}/dispatches" \
  -H "Authorization: Bearer ${LOG_MONITOR_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  -d "$PAYLOAD" || echo "000")"

if [[ "$HTTP_CODE" == "204" ]]; then
  # Success: record the new signatures (start their dedupe TTL) and this dispatch (circuit breaker).
  { cat "$KEPT_SIGS"; for s in "${!SEEN_NEW[@]}"; do echo "$NOW $s"; done; } > "$SIG_FILE"
  awk -v now="$NOW" '($1+3600) > now' "$SEND_FILE" 2>/dev/null > "$SEND_FILE.tmp" || true
  echo "$NOW" >> "$SEND_FILE.tmp"; mv "$SEND_FILE.tmp" "$SEND_FILE"
  echo "[$(date -u +%FT%TZ)] log-monitor: dispatched ${NEW_COUNT} new error signature(s) for commit ${COMMIT:0:12} (service: ${PRIMARY_SVC})."
  exit 0
else
  # Failure: do NOT record signatures, so the next run retries them.
  echo "[$(date -u +%FT%TZ)] log-monitor: dispatch FAILED (HTTP ${HTTP_CODE}). Response:" >&2
  cat /tmp/e1l-logmon-resp >&2 2>/dev/null || true
  echo >&2
  exit 1
fi
