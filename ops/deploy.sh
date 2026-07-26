#!/usr/bin/env bash

set -Eeuo pipefail
# shellcheck source=ops/lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_command docker
require_command curl
require_command flock
load_environment
require_variable SITE_DOMAIN

new_tag="${1:-}"
if [[ ! "${new_tag}" =~ ^(main|sha-[0-9a-f]{7,40})$ ]]; then
  echo "Usage: $0 sha-<git-commit> (or main for an emergency test)" >&2
  exit 1
fi

exec 8>"${PROJECT_DIR}/.deploy.lock"
flock -n 8 || {
  echo "Another deployment is already running." >&2
  exit 1
}

state_file="${PROJECT_DIR}/.deployed-image-tag"
previous_tag="$(cat "${state_file}" 2>/dev/null || printf '%s' "${IMAGE_TAG:-}")"

if container_is_running db; then
  "${OPS_DIR}/backup.sh"
fi

export IMAGE_TAG="${new_tag}"
compose config --quiet
compose pull app
compose up -d --remove-orphans

deployment_ok=true
if ! wait_for_app_health 120; then
  deployment_ok=false
elif ! curl --fail --silent --show-error --retry 12 --retry-delay 5 \
  "https://${SITE_DOMAIN}/login" >/dev/null; then
  deployment_ok=false
fi

if [[ "${deployment_ok}" != "true" ]]; then
  echo "Deployment failed health checks." >&2
  if [[ -n "${previous_tag}" && "${previous_tag}" != "${new_tag}" ]]; then
    echo "Rolling back to ${previous_tag}." >&2
    export IMAGE_TAG="${previous_tag}"
    compose up -d --remove-orphans
    wait_for_app_health 120 || {
      echo "Automatic rollback also failed; manual recovery is required." >&2
      exit 2
    }
  fi
  exit 1
fi

printf '%s\n' "${new_tag}" > "${state_file}"
echo "RepetHelper ${new_tag} is healthy at https://${SITE_DOMAIN}."
