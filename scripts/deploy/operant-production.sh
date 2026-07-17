#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/docker/docker-compose.production.yml"
ENV_FILE="${OPERANT_ENV_FILE:-$ROOT_DIR/infra/docker/.env.production}"
PROJECT_NAME="${OPERANT_COMPOSE_PROJECT:-operant}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing production env file: $ENV_FILE" >&2
  exit 2
fi

compose() {
  docker compose --project-name "$PROJECT_NAME" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

case "${1:-}" in
  validate)
    compose config --quiet
    ;;
  start)
    compose config --quiet
    compose up -d
    ;;
  stop)
    compose down
    ;;
  restart)
    compose config --quiet
    compose up -d
    ;;
  status)
    compose ps
    ;;
  logs)
    compose logs --tail="${OPERANT_LOG_TAIL:-200}" "${2:-}"
    ;;
  *)
    echo "usage: $0 {validate|start|stop|restart|status|logs [service]}" >&2
    exit 2
    ;;
esac
