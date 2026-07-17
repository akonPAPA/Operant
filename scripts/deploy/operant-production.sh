#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/docker/docker-compose.production.yml"
ENV_FILE="${OPERANT_ENV_FILE:-$ROOT_DIR/infra/docker/.env.production}"
PROJECT_NAME="${OPERANT_COMPOSE_PROJECT:-operant}"
STARTUP_TIMEOUT_MIN=30
STARTUP_TIMEOUT_MAX=900
STARTUP_TIMEOUT_DEFAULT=240

fail_usage() {
  echo "usage: $0 {validate|start|stop|restart|status|logs [service]}" >&2
  exit 2
}

fail_config() {
  echo "$1" >&2
  exit 2
}

startup_timeout_seconds() {
  raw="${OPERANT_STARTUP_TIMEOUT_SECONDS-__operant_unset__}"
  if [ "$raw" = "__operant_unset__" ]; then
    raw="$STARTUP_TIMEOUT_DEFAULT"
  fi
  if [ -z "$raw" ]; then
    fail_config "OPERANT_STARTUP_TIMEOUT_SECONDS must be a decimal integer from $STARTUP_TIMEOUT_MIN to $STARTUP_TIMEOUT_MAX"
  fi
  case "$raw" in
    *[!0-9]*)
      fail_config "OPERANT_STARTUP_TIMEOUT_SECONDS must be a decimal integer from $STARTUP_TIMEOUT_MIN to $STARTUP_TIMEOUT_MAX"
      ;;
  esac
  if [ "$raw" -lt "$STARTUP_TIMEOUT_MIN" ] || [ "$raw" -gt "$STARTUP_TIMEOUT_MAX" ]; then
    fail_config "OPERANT_STARTUP_TIMEOUT_SECONDS must be a decimal integer from $STARTUP_TIMEOUT_MIN to $STARTUP_TIMEOUT_MAX"
  fi
  printf '%s\n' "$raw"
}

require_env_file() {
  if [ ! -f "$ENV_FILE" ]; then
    fail_config "Missing production env file: $ENV_FILE"
  fi
}

compose() {
  docker compose --project-name "$PROJECT_NAME" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

require_compose_wait_support() {
  if ! docker compose up --help 2>/dev/null | grep -q -- '--wait'; then
    echo "Docker Compose must support 'up --wait' for bounded production startup" >&2
    exit 2
  fi
}

case "${1:-}" in
  validate)
    require_env_file
    startup_timeout_seconds >/dev/null
    compose config --quiet
    require_compose_wait_support
    ;;
  start)
    require_env_file
    timeout="$(startup_timeout_seconds)"
    compose config --quiet
    require_compose_wait_support
    compose up -d --remove-orphans --wait --wait-timeout "$timeout"
    ;;
  stop)
    require_env_file
    compose stop
    ;;
  restart)
    require_env_file
    timeout="$(startup_timeout_seconds)"
    compose config --quiet
    require_compose_wait_support
    compose up -d --force-recreate --remove-orphans --wait --wait-timeout "$timeout"
    ;;
  status)
    require_env_file
    compose ps
    ;;
  logs)
    require_env_file
    compose logs --tail="${OPERANT_LOG_TAIL:-200}" "${2:-}"
    ;;
  *)
    fail_usage
    ;;
esac
