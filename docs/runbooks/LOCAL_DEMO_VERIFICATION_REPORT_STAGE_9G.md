# Local Demo Verification Report - Stage 9G

## Metadata

- Date/time: 2026-05-19T14:38:15.9238129+05:00
- Verifier: Codex
- Branch: `main`
- Scope: Windows local demo/dev environment recovery after Docker Desktop reinstall.
- Machine assumptions: Windows host, PowerShell shell, Docker Desktop Linux engine, active repository root `C:\OrderPilot\OrderPilot-Core`.

## Guardrails

No generic Docker setup, product feature changes, investor demo UI redesign, backend domain rewrite, AI logic change, Playwright dependency change, npm audit change, hardcoded secret, direct DB write path from frontend/AI/bot, or destructive volume reset was introduced.

Only repo-defined infrastructure was used:

- Compose file: `infra\docker\docker-compose.yml`
- Started services: `postgres`, `redis`
- No `docker compose down -v`
- No volume deletion

## Environment Detection

| Check | Result |
| --- | --- |
| Current repo root | `C:\OrderPilot\OrderPilot-Core` confirmed from `docs\runbooks\ACTIVE_REPOSITORY.md`. |
| Initial cwd | `C:\Users\mukha\Documents\OrderPilot` was not a Git repository; active repo marker points to `C:\OrderPilot\OrderPilot-Core`. |
| Git status | Dirty worktree already existed before this pass; changes were kept scoped. |
| Docker version | `Docker version 29.4.3, build 055a478`. |
| Docker Compose version | `Docker Compose version v5.1.3`. |
| Docker info | Initially failed because Docker daemon was not running; after launching Docker Desktop, server reported Docker Desktop Linux engine, Docker Server Version `29.4.3`. |
| Docker config warning | Docker commands printed `Error loading config file: open C:\Users\mukha\.docker\config.json: Access is denied`; this did not block compose startup after Docker Desktop was launched. |
| Native `psql` | FAIL: `psql` is not on PATH. |
| Native PostgreSQL Windows service | None found. |
| Initial `localhost:5432` | FAIL before Docker startup. |
| Final `localhost:5432` | PASS: `TcpTestSucceeded: True`. |
| Final `localhost:6379` | PASS: `TcpTestSucceeded: True`. |

## Compose Services Found

`docker compose config --services` from `infra\docker` returned:

```text
redis
ai-worker
postgres
core-api
web-dashboard
```

Only `postgres` and `redis` were started for this recovery pass.

## Docker Containers and Volumes

Before startup:

- No existing containers matching `orderpilot` were listed.
- No existing volumes matching `orderpilot` were listed.

After startup:

```text
NAME                IMAGE                STATUS                   PORTS
docker-postgres-1   postgres:16-alpine   Up 3 minutes (healthy)   0.0.0.0:5432->5432/tcp, [::]:5432->5432/tcp
docker-redis-1      redis:7-alpine       Up 3 minutes (healthy)   0.0.0.0:6379->6379/tcp, [::]:6379->6379/tcp
```

Volume:

```text
docker_orderpilot_postgres   local
```

Postgres container verification:

```text
PostgreSQL 16.14 on x86_64-pc-linux-musl
```

Redis container verification:

```text
PONG
```

## Script Changes

`scripts\seed-local-demo.ps1` now preserves native `psql` when available and falls back to the repo-defined Docker Compose `postgres` service when local `psql` is unavailable.

Fallback constraints:

- Uses `infra\docker\docker-compose.yml`.
- Requires the compose `postgres` service to be running.
- Uses the parsed JDBC database name and configured username.
- Only uses Docker Compose fallback for localhost datasources.
- Does not hardcode real secrets.
- Does not delete or recreate volumes.

## Required Flow Results

| Command | Result |
| --- | --- |
| `powershell -ExecutionPolicy Bypass -File .\scripts\seed-local-demo.ps1 -UpdateFrontendEnv` before Flyway schema initialization | FAIL: Docker psql fallback worked, but database tables did not exist yet: `ERROR: relation "tenant" does not exist`. |
| `powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1` | PASS_WITH_LIMITATIONS: prerequisites passed, frontend started/responded, backend process was launched but did not stay running. |
| Foreground `mvn spring-boot:run` from `apps\core-api` | FAIL: Flyway validated/applied 8 migrations, then Hibernate schema validation failed on `customer_account.default_currency`. |
| `powershell -ExecutionPolicy Bypass -File .\scripts\seed-local-demo.ps1 -UpdateFrontendEnv` after Flyway initialized schema | PASS: seed SQL committed through Docker Compose psql fallback and `.env.local` was updated with demo-safe IDs. |
| `powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1` after seed | PASS_WITH_LIMITATIONS: Postgres reachable, frontend already responded, backend launched but did not stay running. |
| `powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode` | FAIL: frontend routes passed, backend was not listening on `localhost:8080`, so backend/API probes failed. |
| `powershell -ExecutionPolicy Bypass -File .\scripts\check-no-secrets.ps1` | PASS: `No obvious hardcoded secrets found.` |

## Backend Blocker

The remaining blocker is not Docker, Redis, Postgres reachability, or native `psql`.

Backend runtime fails during Hibernate schema validation:

```text
Schema-validation: wrong column type encountered in column [default_currency] in table [customer_account];
found [bpchar (Types#CHAR)], but expecting [varchar(255) (Types#VARCHAR)]
```

Evidence in source:

- `apps\core-api\src\main\resources\db\migration\V2__data_foundation_import_mirror.sql` defines `customer_account.default_currency CHAR(3)`.
- `apps\core-api\src\main\java\com\orderpilot\domain\customer\CustomerAccount.java` maps `defaultCurrency` as a default `String` column, so Hibernate expects `VARCHAR(255)`.

This is a schema/domain mapping mismatch. It was not changed in this environment recovery pass because backend domain and migration changes were outside the allowed scope.

## `check-local-demo.ps1 -AllowFixtureMode` Detail

Passing checks:

- Java, Maven, Node, and npm found.
- Required repo paths found.
- Frontend dependencies found.
- `.env.local` found.
- Demo tenant/product/location IDs configured.
- Postgres reachable at `localhost:5432`.
- Next.js build output found.
- Frontend port `3000` listening.
- Frontend routes returned HTTP 200:
  - `/demo`
  - `/command-center`
  - `/inbox`
  - `/bot-conversations`
  - `/bot/conversations`
  - `/reconciliation`
  - `/analytics`
  - `/audit-log`
  - `/integrations`

Failing checks:

- Backend not listening on `localhost:8080`.
- Core API health unreachable.
- Demo Telegram RFQ webhook unreachable.
- Demo inventory reconciliation run unreachable.
- Demo reconciliation cases endpoint unreachable.
- Demo commerce analytics summary endpoint unreachable.

## Exact Commands Run

```powershell
Get-Location
git status --short --branch
Get-Content docs\runbooks\ACTIVE_REPOSITORY.md
Get-Content docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9F.md
Get-Content scripts\seed-local-demo.ps1
Get-Content scripts\start-local-demo.ps1
Get-Content scripts\check-local-demo.ps1
Get-Content scripts\check-no-secrets.ps1
Get-Content infra\docker\docker-compose.yml
Get-Content apps\web-dashboard\.env.local.example
Get-Content apps\core-api\.env.example
Get-Content apps\web-dashboard\package.json
Get-Content docs\runbooks\DEPENDENCY_AUDIT_NOTES.md
Get-Content docs\investor\DEMO_SCREENSHOT_CHECKLIST.md
Get-Content docs\investor\INVESTOR_DEMO_SCRIPT_CORE_V1.md
Get-Content docs\investor\INVESTOR_DEMO_HANDOFF.md
Get-Content docs\investor\DEMO_DATASET_CORE_V1.md
rg -n "demo|dashboard|reconciliation|bot|analytics" apps\web-dashboard\tests apps\web-dashboard -g "*.test.*" -g "*.spec.*"
docker --version
docker compose version
docker info
docker compose config --services
psql --version
Test-NetConnection localhost -Port 5432
Get-Service | Where-Object { $_.Name -match 'postgres|pgsql|docker|com\.docker' -or $_.DisplayName -match 'PostgreSQL|Docker' }
docker ps -a --filter "name=orderpilot"
docker volume ls --filter "name=orderpilot"
Start-Service com.docker.service
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
docker compose up -d postgres redis
docker compose ps postgres redis
Test-NetConnection localhost -Port 5432
Test-NetConnection localhost -Port 6379
docker compose exec -T postgres psql -U orderpilot_app -d orderpilot -c "select version();"
docker compose exec -T redis redis-cli ping
powershell -ExecutionPolicy Bypass -File .\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1
Invoke-WebRequest http://localhost:8080/api/v1/health
Invoke-WebRequest http://localhost:3000/demo
mvn spring-boot:run
powershell -ExecutionPolicy Bypass -File .\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
powershell -ExecutionPolicy Bypass -File .\scripts\check-no-secrets.ps1
docker compose ps postgres redis
docker volume ls --filter "name=orderpilot"
docker ps -a --filter "name=docker-"
rg -n "default_currency|currency" apps\core-api\src\main\java\com\orderpilot\domain\customer apps\core-api\src\main\resources\db\migration
```

## Changed Files

- `scripts\seed-local-demo.ps1`
- `apps\web-dashboard\.env.local`
- `docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9G.md`

## Final Status

`PASS_WITH_LIMITATIONS`

Docker Desktop is restored enough for the repo-defined Postgres and Redis services. Postgres is listening on `localhost:5432`, Redis is listening on `localhost:6379`, the local demo seed can run through Docker Compose when native `psql` is missing, the frontend is live, and no obvious hardcoded secrets were found.

The full local demo is not fully working because the Core API backend fails startup on a schema/entity mismatch after Flyway initializes the database.

## Next Recommended Step

Fix the backend schema/entity mismatch for `customer_account.default_currency` in a separate, explicitly authorized backend migration/mapping pass, then rerun `start-local-demo.ps1` and `check-local-demo.ps1 -AllowFixtureMode`.
