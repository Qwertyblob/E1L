# AI log-monitor (Direct path)

When the live site logs an error, the VPS detects it and fires a GitHub event that
**opens (or comments on) a labeled issue** carrying the error context — so you get a push
notification (GitHub mobile / email) without touching the VPS. No LLM sits in the alert path,
so it can't be rate-limited, can't "simulate" the action, and costs nothing per run. You triage
the issue: a code bug → open a fix PR; a config/infra problem → run the command on the VPS.

```
VPS cron: scripts/log-monitor.sh   (every 5 min)
   │  detect ERROR/FATAL/Exception in recent container logs
   │  dedupe + debounce + hourly circuit-breaker
   │  curl -> GitHub repository_dispatch  (event_type: prod-error)
   ▼
.github/workflows/log-monitor.yml
   │  deterministically opens / comments the [log-monitor] issue with the bundled context
   ▼
GitHub issue  → push notification → you triage (fix PR for code, run command for config)
```

## One-time setup

1. **GitHub token.** Generate a **fine-grained PAT** scoped to **only this repo**
   (`Qwertyblob/E1L`) with **Contents: read & write** (the permission the `repository_dispatch`
   API requires). Scoping to one repo means a leaked token can't touch anything else. No machine
   account or AI-provider API key is needed — the workflow uses the auto-issued `GITHUB_TOKEN`.

2. **VPS `.env`.** On the server (`~/E1L/.env`), set:
   ```
   GITHUB_REPO=Qwertyblob/E1L
   LOG_MONITOR_TOKEN=<the fine-grained PAT from step 1>
   ```
   (Optional tuning knobs are documented in `.env.example`.)

3. **Install the cron job.** Re-run the ops crontab installer on the server:
   ```
   bash ~/E1L/scripts/setup-backup-cron.sh
   ```
   It also schedules `log-monitor.sh` every 5 min, logging to `~/.local/state/every1luvs/logs/log-monitor.log`.

4. **Turn on notifications.** Watch `Qwertyblob/E1L` (or enable issue notifications) and install
   the **GitHub mobile app** — that's how the alert reaches you off the VPS.

## Cost guards (built in)

- **Debounce** — one dispatch per run; a 5-min window is batched into a single call.
- **Dedupe** — each distinct error signature is dispatched at most once per `LOG_MONITOR_DEDUPE_TTL`
  (default 6h), tracked in `~/.e1l-log-monitor/signatures`. The workflow also de-dupes by issue
  title, so a repeat comments on the existing issue instead of opening a new one.
- **Circuit breaker** — hard cap of `LOG_MONITOR_MAX_PER_HOUR` dispatches/hour (default 3),
  tracked in `~/.e1l-log-monitor/sends`. A crash loop can't spam your issues.

## Test it

Fire a synthetic dispatch from the VPS to confirm the workflow runs. Load `.env` first so the
token is in your shell (your login shell doesn't read it automatically):
```
set -a; . ~/E1L/.env; set +a
curl -sS -o /dev/null -w '%{http_code}\n' -X POST \
  https://api.github.com/repos/Qwertyblob/E1L/dispatches \
  -H "Authorization: Bearer $LOG_MONITOR_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d '{"event_type":"prod-error","client_payload":{"commit":"main","primary_service":"app","log_excerpt":"ERROR test — synthetic log-monitor check"}}'
```
A successful dispatch prints `204` (empty body). Then watch the **Actions** tab for the
"AI Log Monitor" run, and the **Issues** tab for a new `[log-monitor]` issue.

## Optional: add an AI diagnosis later

If you want an AI's root-cause suggestion, do it as a **separate, non-blocking step** that runs
*after* the issue is created: have the model emit its analysis as **text**, then a plain
`gh issue comment` step posts it. Keep the model off the critical path — the alert must never
depend on a provider's availability, rate limits, or sandboxed tool execution.

## When something goes wrong

- **No dispatch fired:** `tail ~/.local/state/every1luvs/logs/log-monitor.log` on the VPS. `dispatch FAILED (HTTP
  401/403)` means the token is wrong/under-scoped or not loaded into the shell; `CIRCUIT OPEN`
  means the hourly cap was hit.
- **Dispatch fired but no issue:** check the workflow run in the Actions tab. A 204 from the curl
  only confirms GitHub accepted the event — the run log shows whether `gh issue create` succeeded.
