#!/bin/sh
set -eu

: "${POSTGRES_DB:?set POSTGRES_DB}"
: "${POSTGRES_USER:?set POSTGRES_USER}"
: "${ORDERPILOT_DB_MIGRATOR_PASSWORD:?set ORDERPILOT_DB_MIGRATOR_PASSWORD}"
: "${ORDERPILOT_DB_RUNTIME_PASSWORD:?set ORDERPILOT_DB_RUNTIME_PASSWORD}"

psql \
  --host="${POSTGRES_HOST:-postgres}" \
  --username="${POSTGRES_USER}" \
  --dbname="${POSTGRES_DB}" \
  --set=ON_ERROR_STOP=1 \
  --set=db_name="${POSTGRES_DB}" \
  --set=migrator_password="${ORDERPILOT_DB_MIGRATOR_PASSWORD}" \
  --set=runtime_password="${ORDERPILOT_DB_RUNTIME_PASSWORD}" \
  --file=/opt/operant/provision-production-roles.sql
