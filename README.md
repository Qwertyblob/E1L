# every1luvs

Online booking site for the every1luvs nail salon — live at **[every1luvs.com](https://every1luvs.com)**.
Guests browse services and book appointments; an admin manages availability and bookings.

A React single-page app talks to a Spring Boot REST API over same-origin `/api`. In production the
whole stack runs as Docker containers behind a Cloudflare Tunnel.

## Stack

| Layer    | Tech |
|----------|------|
| Frontend | React 19 (Create React App), nginx static serving |
| Backend  | Spring Boot 4, Java 21, Maven |
| Database | PostgreSQL 17, Flyway migrations |
| Auth     | JWT in an httpOnly cookie + cookie-based CSRF, BCrypt passwords |
| Infra    | Docker Compose, Cloudflare Tunnel ingress, images on GHCR |
| CI/CD    | GitHub Actions (test → build → push → SSH deploy) |

## Repository layout

```
every1luvs/        Spring Boot REST API (controller → service → repository → entity)
client/            React SPA (Create React App)
web/               Dockerfile that builds the SPA and serves it via nginx
nginx/             nginx config: realip, security headers/CSP, SPA fallback, /api proxy
scripts/           Ops: R2 backups, mail-failure alerts, AI log-monitor
.github/workflows/ ci.yml (tests), deploy.yml (CD), log-monitor.yml
docker-compose.yml Production stack definition
.env.example       Template for the server-side .env (never commit the real one)
```

## Local development

Prerequisites: Java 21, Node 22, and a local PostgreSQL with a database named `every1luvs`.

**Backend** (from `every1luvs/`):

```bash
./mvnw spring-boot:run     # runs against 127.0.0.1:5432/every1luvs by default
./mvnw -B test             # run the test suite
```

Provide at minimum `AUTH_TOKEN_SECRET` (32+ chars) via environment; see
`every1luvs/src/main/resources/application.properties` for all settings and their defaults.

**Frontend** (from `client/`):

```bash
npm install
npm start                          # http://localhost:3000, proxies /api to :8080
npm test -- --watchAll=false       # run tests once
npm run build                      # production bundle
```

### Editing services and prices

`client/src/services.json` is the **single source of truth** for bookable services, technician
tiers, add-ons, prices, and durations. The frontend renders from it, and the Maven build copies it
onto the backend classpath so the server recomputes booking totals from the same data. Edit prices
in that one file.

## Automated customer emails

The booking flow sends a series of lifecycle emails. All are **best-effort** (async / off the
request thread), so a mail hiccup never fails a booking. The time-based ones run as scheduled
sweeps (Asia/Singapore) and are **idempotent** — each is sent at most once per booking, tracked by
a `*_sent_at` column, and retried on the next sweep if a send fails.

| Email | When | Sent to |
|-------|------|---------|
| **Confirmation** | Immediately on booking | Everyone who books |
| **Reminder** | ~2 days before the appointment | Active (`BOOKED`) bookings |
| **Review request** | Right after the appointment is marked **Completed** (+ a fallback sweep) | `COMPLETED` bookings only |
| **Rebooking prompt** | ~3 weeks after the appointment | `COMPLETED` bookings only |

The admin also gets a full copy of every booking (with any inspo photos) on booking.

**No-show handling:** the review and rebooking emails go **only** to bookings an admin has marked
`COMPLETED`. A no-show is never marked completed, so it is never asked to review or nudged to
rebook — no separate "no-show" status is needed.

**Destination links** (both on by default — override in the server `.env` if needed):

- `REVIEW_URL` — where the review email links. **Defaults to the Instagram page**
  (`https://instagram.com/every1luvsnails`). Override with a dedicated review page (e.g. a Google
  Business "write a review" link) when you have one.
- `BOOKING_URL` — where the rebooking button links. **Defaults to the site's services section**
  (`https://every1luvs.com/#services`), where booking happens.

Set these in the server `.env` (see `.env.example`). Confirmation and reminder emails need no extra
config beyond SMTP.

## Testing

- Backend: `./mvnw -B test` (JUnit, JaCoCo coverage at `target/site/jacoco/`).
- Frontend: `npm test -- --watchAll=false` (React Testing Library + Jest).

Both run automatically in CI on every push to `main` and every pull request.

## Deployment

Continuous deployment is fully automated:

1. Push to `main` → **CI** (`ci.yml`) runs backend and frontend tests plus a production build.
2. On CI success → **Deploy** (`deploy.yml`) builds the `app` and `web` Docker images, pushes them
   to GHCR, then SSHes to the VPS and rolls the stack with `git pull --ff-only` +
   `docker compose pull && up -d`.

The production stack (`docker-compose.yml`):

```
Cloudflare Tunnel → web (nginx)  → app (Spring Boot, :8080 internal) → db (Postgres 17)
                    static edge IP   never published to host             data network only
```

No host ports are published — the only ingress is the Cloudflare Tunnel. The three Docker networks
(`edge`, `appnet`, `data`) keep the database reachable only by the app.

### Server setup

```bash
cp .env.example .env        # then fill in real values (chmod 600)
docker compose up -d --build
```

`.env` holds database credentials, `AUTH_TOKEN_SECRET`, SMTP settings, the Cloudflare tunnel token,
the one-time admin bootstrap, and ops tokens. See `.env.example` for the full annotated list.

> **Note:** the VPS deploy runs `git pull --ff-only` against the server checkout. Never hand-edit
> repository files on the server — it will break the next deploy.

## Operations

Scripts in `scripts/` (see `scripts/LOG-MONITOR.md` for the log-monitor details):

- **Backups** — `backup-to-r2.sh` / `setup-backup-cron.sh` dump Postgres to Cloudflare R2;
  `restore-from-r2.sh` restores.
- **Mail alerts** — `check-mail-failures.sh` surfaces async mail-delivery failures.
- **AI log-monitor** — `log-monitor.sh` (cron, every 5 min) scans container logs for errors and
  fires a GitHub `repository_dispatch` so a workflow can open an issue for review.

Health: the app exposes only `/actuator/health` (used by the compose healthcheck); it is never
published to the host.
