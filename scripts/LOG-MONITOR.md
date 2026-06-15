# AI log-monitor (Direct path)

When the live site logs an error, the VPS detects it and wakes Claude Code, which opens a PR
(code fix) or an issue (config/infra fix) for you to review and approve.

```
VPS cron: scripts/log-monitor.sh   (every 5 min)
   │  detect ERROR/FATAL/Exception in recent container logs
   │  dedupe + debounce + hourly circuit-breaker
   │  curl -> GitHub repository_dispatch  (event_type: prod-error)
   ▼
.github/workflows/log-monitor.yml
   │  checks out the DEPLOYED commit, runs Claude Code on the bundled context
   ▼
PR (code fix)  or  Issue (config/infra fix)   → you review → merge → CD deploys
```

## One-time setup

1. **GitHub machine account + token.** Create a dedicated GitHub account (e.g. `e1l-bot`), give
   it push access to `Qwertyblob/E1L`, and generate a **fine-grained PAT** scoped to **only this
   repo** with **Contents: read & write** (the permission the dispatch API requires). Scoping to
   one repo means a leaked token can't touch anything else.

2. **Repo secret.** In the repo: Settings → Secrets and variables → Actions → add
   `ANTHROPIC_API_KEY` (the workflow needs it to run Claude).

3. **VPS `.env`.** On the server (`~/E1L/.env`), set:
   ```
   GITHUB_REPO=Qwertyblob/E1L
   LOG_MONITOR_TOKEN=<the fine-grained PAT from step 1>
   ```
   (Optional tuning knobs are documented in `.env.example`.)

4. **Install the cron job.** Re-run the ops crontab installer on the server:
   ```
   bash ~/E1L/scripts/setup-backup-cron.sh
   ```
   It now also schedules `log-monitor.sh` every 5 min, logging to `/var/log/e1l-log-monitor.log`.

## Cost guards (built in)

- **Debounce** — one dispatch per run; a 5-min window is batched into a single call.
- **Dedupe** — each distinct error signature is dispatched at most once per `LOG_MONITOR_DEDUPE_TTL`
  (default 6h), tracked in `~/.e1l-log-monitor/signatures`.
- **Circuit breaker** — hard cap of `LOG_MONITOR_MAX_PER_HOUR` dispatches/hour (default 3),
  tracked in `~/.e1l-log-monitor/sends`. A crash loop can't run up an AI bill.

## Test it

Fire a synthetic dispatch from the VPS (or anywhere with the token) to confirm the workflow runs:
```
curl -X POST https://api.github.com/repos/Qwertyblob/E1L/dispatches \
  -H "Authorization: Bearer $LOG_MONITOR_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d '{"event_type":"prod-error","client_payload":{"commit":"main","primary_service":"app","log_excerpt":"ERROR test — please diagnose and open an issue titled [log-monitor] test"}}'
```
Then watch the **Actions** tab for the "AI Log Monitor" run.

## When something goes wrong

- **No dispatch fired:** `tail /var/log/e1l-log-monitor.log` on the VPS. A `dispatch FAILED (HTTP
  401/403)` means the token is wrong or under-scoped; `CIRCUIT OPEN` means the hourly cap was hit.
- **Dispatch fired but no PR/issue:** check the workflow run in the Actions tab (bad/missing
  `ANTHROPIC_API_KEY`, or Claude decided a matching PR/issue already exists).
