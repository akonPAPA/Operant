#!/bin/sh
set -eu

test_db="${ORDERPILOT_TEST_DB_NAME:-orderpilot_test}"
test_user="${ORDERPILOT_TEST_DB_USER:-$POSTGRES_USER}"
test_password="${ORDERPILOT_TEST_DB_PASSWORD:-$POSTGRES_PASSWORD}"

if [ "$test_user" != "$POSTGRES_USER" ]; then
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${test_user}') THEN
    CREATE ROLE "${test_user}" LOGIN PASSWORD '${test_password}';
  END IF;
END
\$\$;
EOSQL
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
SELECT 'CREATE DATABASE "${test_db}" OWNER "${test_user}"'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${test_db}')\gexec
GRANT ALL PRIVILEGES ON DATABASE "${test_db}" TO "${test_user}";
EOSQL
