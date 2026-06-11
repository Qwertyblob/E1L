#!/usr/bin/env bash
#
# Nightly off-box Postgres backup: pg_dump (inside the db container) -> gzip -> Cloudflare R2.
#
# One-time setup on the host:
#   1. Install rclone:            curl https://rclone.org/install.sh | sudo bash
#   2. Configure an R2 remote named `r2` (S3-compatible):  rclone config
#      (provider: Cloudflare R2; supply account R2 access key/secret + endpoint)
#   3. Make this executable:      chmod +x scripts/backup-to-r2.sh
#   4. Schedule it (crontab -e), e.g. nightly at 03:15:
#        15 3 * * * /opt/every1luvs/scripts/backup-to-r2.sh >> /var/log/e1l-backup.log 2>&1
#
# Retention: set a lifecycle rule on the R2 bucket (e.g. delete after 30 days) to stay free-tier.

set -euo pipefail

# Resolve repo root (the compose project dir), regardless of where cron invokes us from.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Load DB creds / bucket from the same .env compose uses.
set -a
# shellcheck disable=SC1091
source ./.env
set +a

STAMP="$(date -u +%Y-%m-%d)"
BUCKET="${R2_BUCKET:-every1luvs-backups}"
KEY="every1luvs/db-${STAMP}.sql.gz"

echo "[$(date -u +%FT%TZ)] starting backup -> r2:${BUCKET}/${KEY}"

# Stream the dump straight to R2 without ever touching local disk.
docker compose exec -T db pg_dump -U "$DB_USERNAME" -d every1luvs \
  | gzip -9 \
  | rclone rcat "r2:${BUCKET}/${KEY}"

echo "[$(date -u +%FT%TZ)] backup uploaded: r2:${BUCKET}/${KEY}"
