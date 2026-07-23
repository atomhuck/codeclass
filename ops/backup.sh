#!/usr/bin/env bash

set -Eeuo pipefail
# shellcheck source=ops/lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_command docker
require_command restic
require_command flock
load_environment
require_variable POSTGRES_DB
require_variable POSTGRES_USER
require_variable RESTIC_REPOSITORY
require_variable RESTIC_PASSWORD
require_variable AWS_ACCESS_KEY_ID
require_variable AWS_SECRET_ACCESS_KEY
require_secret_variable RESTIC_PASSWORD 24
require_secret_variable AWS_SECRET_ACCESS_KEY 16

exec 9>"${PROJECT_DIR}/.backup.lock"
flock -n 9 || {
  echo "Another backup is already running." >&2
  exit 1
}

container_is_running db || {
  echo "Database container is not running; backup was not created." >&2
  exit 1
}

snapshot_dir="$(mktemp -d -t codeclass-backup-XXXXXX)"
app_stopped=false

cleanup() {
  local exit_code=$?
  if [[ "${app_stopped}" == "true" ]]; then
    compose start app >/dev/null || true
  fi
  rm -rf -- "${snapshot_dir}"
  exit "${exit_code}"
}
trap cleanup EXIT INT TERM

if container_is_running app; then
  compose stop -t 30 app
  app_stopped=true
fi

compose exec -T db pg_dump \
  --username="${POSTGRES_USER}" \
  --dbname="${POSTGRES_DB}" \
  --format=custom \
  --no-owner \
  --no-privileges > "${snapshot_dir}/database.dump"

docker run --rm \
  --volume codeclass-attachments:/source:ro \
  --volume "${snapshot_dir}:/backup" \
  alpine:3.22 \
  tar -C /source -czf /backup/attachments.tar.gz .

cp -- "${COMPOSE_FILE}" "${PROJECT_DIR}/Caddyfile" "${snapshot_dir}/"
{
  printf 'created_at=%s\n' "$(date --iso-8601=seconds)"
  printf 'image_tag=%s\n' "${IMAGE_TAG:-unknown}"
} > "${snapshot_dir}/manifest.txt"

if [[ "${app_stopped}" == "true" ]]; then
  compose start app
  wait_for_app_health 120 || {
    echo "Application did not recover after the backup snapshot." >&2
    exit 1
  }
  app_stopped=false
fi

(
  cd "${snapshot_dir}"
  restic backup . --host "$(hostname -f)" --tag codeclass
)

restic forget \
  --host "$(hostname -f)" \
  --tag codeclass \
  --keep-daily 7 \
  --keep-weekly 4 \
  --keep-monthly 3 \
  --prune

echo "Database and attachment backup completed."
