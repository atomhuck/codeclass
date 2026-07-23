#!/usr/bin/env bash

set -Eeuo pipefail

OPS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "${OPS_DIR}/.." && pwd)"
ENV_FILE="${ENV_FILE:-${PROJECT_DIR}/.env.production}"
COMPOSE_FILE="${PROJECT_DIR}/compose.production.yaml"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command is missing: $1" >&2
    exit 1
  }
}

load_environment() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "Production environment file not found: ${ENV_FILE}" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
}

require_variable() {
  if [[ -z "${!1:-}" ]]; then
    echo "Required variable is missing in ${ENV_FILE}: $1" >&2
    exit 1
  fi
}

require_secret_variable() {
  local name="$1"
  local minimum_length="$2"
  local value="${!name:-}"
  if (( ${#value} < minimum_length )) || [[ "${value}" == replace-* ]]; then
    echo "${name} must contain at least ${minimum_length} characters and not be a placeholder." >&2
    exit 1
  fi
}

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

container_is_running() {
  local service="$1"
  local container_id
  container_id="$(compose ps -q "${service}")"
  [[ -n "${container_id}" ]] && [[ "$(docker inspect --format '{{.State.Running}}' "${container_id}")" == "true" ]]
}

wait_for_app_health() {
  local timeout_seconds="${1:-120}"
  local container_id
  container_id="$(compose ps -q app)"
  [[ -n "${container_id}" ]] || return 1

  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    local status
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")"
    case "${status}" in
      healthy) return 0 ;;
      unhealthy) return 1 ;;
    esac
    sleep 3
  done
  return 1
}
