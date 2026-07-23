#!/usr/bin/env bash

set -Eeuo pipefail
# shellcheck source=ops/lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_command docker
require_command restic
load_environment
require_variable RESTIC_REPOSITORY
require_variable RESTIC_PASSWORD
require_variable AWS_ACCESS_KEY_ID
require_variable AWS_SECRET_ACCESS_KEY
require_secret_variable RESTIC_PASSWORD 24
require_secret_variable AWS_SECRET_ACCESS_KEY 16

snapshot="${1:-latest}"
restore_dir="$(mktemp -d -t codeclass-restore-XXXXXX)"
suffix="$(date +%s)-$$"
container="codeclass-restore-${suffix}"
database_volume="codeclass-restore-db-${suffix}"
attachment_volume="codeclass-restore-files-${suffix}"
network="codeclass-restore-${suffix}"
test_password="restore-${suffix}"

cleanup() {
  local exit_code=$?
  docker rm -f "${container}" >/dev/null 2>&1 || true
  docker volume rm "${database_volume}" "${attachment_volume}" >/dev/null 2>&1 || true
  docker network rm "${network}" >/dev/null 2>&1 || true
  rm -rf -- "${restore_dir}"
  exit "${exit_code}"
}
trap cleanup EXIT INT TERM

restic restore "${snapshot}" --tag codeclass --target "${restore_dir}"
[[ -s "${restore_dir}/database.dump" ]] || {
  echo "Restored snapshot does not contain database.dump." >&2
  exit 1
}
[[ -s "${restore_dir}/attachments.tar.gz" ]] || {
  echo "Restored snapshot does not contain attachments.tar.gz." >&2
  exit 1
}

docker network create "${network}" >/dev/null
docker volume create "${database_volume}" >/dev/null
docker volume create "${attachment_volume}" >/dev/null
docker run -d --name "${container}" \
  --network "${network}" \
  --volume "${database_volume}:/var/lib/postgresql/data" \
  --env POSTGRES_DB=codeclass_restore \
  --env POSTGRES_USER=codeclass_restore \
  --env POSTGRES_PASSWORD="${test_password}" \
  postgres:17-alpine >/dev/null

deadline=$((SECONDS + 60))
until docker exec "${container}" pg_isready -U codeclass_restore -d codeclass_restore >/dev/null 2>&1; do
  (( SECONDS < deadline )) || {
    echo "Temporary restore database did not become ready." >&2
    exit 1
  }
  sleep 2
done

docker exec -i "${container}" pg_restore \
  --username=codeclass_restore \
  --dbname=codeclass_restore \
  --no-owner \
  --no-privileges < "${restore_dir}/database.dump"

table_count="$(docker exec "${container}" psql \
  --username=codeclass_restore \
  --dbname=codeclass_restore \
  --tuples-only \
  --no-align \
  --command="select count(*) from information_schema.tables where table_schema = 'public';")"
[[ "${table_count}" -gt 0 ]] || {
  echo "Restore test database contains no application tables." >&2
  exit 1
}

docker run --rm \
  --volume "${attachment_volume}:/target" \
  --volume "${restore_dir}:/backup:ro" \
  alpine:3.22 \
  tar -C /target -xzf /backup/attachments.tar.gz

echo "Restore test passed with ${table_count} database tables; production volumes were not touched."
