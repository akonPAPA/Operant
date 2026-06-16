# Local Postgres for OrderPilot integration tests

## Why host port 15432 (not 55432)

`55432` falls inside a **Windows-reserved TCP port range** (e.g. `55363–55462`, reserved by
Hyper-V / WSL / Docker Desktop). Docker cannot bind a host port inside a reserved range, so
`docker compose up` failed with a port permission/bind error and Spring/Flyway could not connect.

Check the reserved ranges on your machine:

```powershell
netsh int ipv4 show excludedportrange tcp
```

Local dev/test now defaults to **host `15432` → container `5432`** everywhere:

| File | Default |
| --- | --- |
| `infra/docker/docker-compose.yml` | `${ORDERPILOT_DB_HOST_PORT:-15432}:5432` |
| `infra/docker/docker-compose.test.yml` | `${ORDERPILOT_TEST_DB_HOST_PORT:-15432}:5432` |
| `apps/core-api/src/main/resources/application.yml` | `localhost:${ORDERPILOT_DB_HOST_PORT:15432}` |
| `apps/core-api/src/test/resources/application-integration-test.yml` | `localhost:${ORDERPILOT_TEST_DB_HOST_PORT:${ORDERPILOT_DB_HOST_PORT:15432}}` |

The **container internal port stays `5432`**; only the host-published port changed.

> If you copy `infra/docker/.env.example` to `.env`, set `ORDERPILOT_DB_HOST_PORT=15432`
> and `ORDERPILOT_TEST_DB_HOST_PORT=15432` (the example file still seeds the old `55432`).

## Quickest path

```powershell
cd C:\OrderPilot\OrderPilot-Core\infra\docker
./reset-local-test-db.ps1
```

This is scoped to the OrderPilot Compose project only — no global Docker prune, no unrelated
resources touched.

## Manual commands (equivalent, scoped to OrderPilot only)

```powershell
cd C:\OrderPilot\OrderPilot-Core\infra\docker
$env:ORDERPILOT_DB_HOST_PORT = "15432"
$env:ORDERPILOT_TEST_DB_HOST_PORT = "15432"

# Inspect before changing anything
docker compose config
docker compose ps
docker ps --format "table {{.Names}}`t{{.Ports}}"

# Stop ONLY OrderPilot services + ITS orphans, then start ONLY postgres
docker compose down --remove-orphans
docker compose up -d postgres
docker ps --format "table {{.Names}}`t{{.Ports}}"   # expect 0.0.0.0:15432->5432/tcp

Test-NetConnection localhost -Port 15432            # expect TcpTestSucceeded : True

# Reset ONLY the orderpilot_test database (no other DB touched)
docker exec -i orderpilot-postgres psql -U orderpilot_local_user -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'orderpilot_test';"
docker exec -i orderpilot-postgres psql -U orderpilot_local_user -d postgres -c "DROP DATABASE IF EXISTS orderpilot_test;"
docker exec -i orderpilot-postgres psql -U orderpilot_local_user -d postgres -c "CREATE DATABASE orderpilot_test;"
```

## Run the integration tests

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:15432/orderpilot_test"

# One targeted test first
mvn "-Dspring.profiles.active=integration-test" "-Dtest=AuditIdempotencyPostgresIntegrationTest" test

# Only if green, run the rest
mvn "-Dspring.profiles.active=integration-test" "-Dtest=*IntegrationTest" test
```

Credentials default to the Compose Postgres env: user `orderpilot_local_user`,
password `change-me-local-dev-only` (override via `ORDERPILOT_DB_USER` / `ORDERPILOT_DB_PASSWORD`
or `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`).

## If a test still fails, extract the first real root cause only

```powershell
Select-String -Path "target\surefire-reports\*.txt" `
  -Pattern "Caused by:|Flyway|PSQLException|BeanCreationException|relation .* does not exist|already exists|checksum|Validate failed|password authentication failed|permission denied" `
  -Context 2,8 | Select-Object -First 180
```
