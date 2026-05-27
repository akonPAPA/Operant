# Local Development Runbook

This runbook is the primary local development path for OrderPilot on Windows.

The live repository is no longer a README-only or Stage 1-only scaffold. It contains later-stage backend, frontend, AI-worker, connector, intake, validation, workspace, and demo modules. Use this runbook to verify the shared foundation without resetting, deleting, or recreating the project.

OrderPilot uses:

- Java 21 and Spring Boot for `apps/core-api`
- Next.js and TypeScript for `apps/web-dashboard`
- Python 3.12+ for `apps/ai-worker`
- PostgreSQL and Redis through Docker Compose
- Flyway migrations owned by the backend

Frontend, AI worker, chatbot, and connector code must not write directly to PostgreSQL. Trusted business writes go through `apps/core-api`.

## Prerequisites

Install and start:

- Docker Desktop with Docker Compose v2
- Java 21
- Maven 3.9+
- Node.js 20 LTS for local frontend work
- Python 3.12+

PowerShell should run commands from the active repository:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
pwd
dir
```

If npm is blocked by the PowerShell execution policy, use `npm.cmd` in the same commands.

## Local Database Convention

Local development uses these non-production values:

```powershell
POSTGRES_DB=orderpilot
POSTGRES_USER=orderpilot
POSTGRES_PASSWORD=orderpilot_dev_password
```

The backend datasource must match:

```powershell
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/orderpilot
SPRING_DATASOURCE_USERNAME=orderpilot
SPRING_DATASOURCE_PASSWORD=orderpilot_dev_password
```

Inside Docker Compose, `core-api` connects to `jdbc:postgresql://postgres:5432/orderpilot`. On the Windows host, Compose publishes PostgreSQL to `localhost:55432` by default to avoid collisions with any native PostgreSQL service on `localhost:5432`.

## Start Local Infrastructure

The primary Compose file is `infra/docker/docker-compose.yml`. Run it from the repo root:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
Copy-Item ".env.example" ".env" -Force
docker compose -f "infra/docker/docker-compose.yml" up -d postgres redis
docker compose -f "infra/docker/docker-compose.yml" ps
```

Useful checks:

```powershell
docker ps
docker logs orderpilot-postgres
docker exec -it orderpilot-postgres psql -U orderpilot -d orderpilot
```

Do not use `psql -U postgres -d postgres` for this repo. The local Compose config creates the `orderpilot` role and `orderpilot` database.

## Start All Services With Docker Compose

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
docker compose -f "infra/docker/docker-compose.yml" up --build
```

Ports:

- PostgreSQL from Windows host tools: `localhost:55432`
- PostgreSQL inside Docker Compose: `postgres:5432`
- Redis: `localhost:6379`
- Core API: `http://localhost:8080`
- Web dashboard: `http://localhost:3000`

## Backend

Start infra first, then run the backend locally:

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:55432/orderpilot"
$env:SPRING_DATASOURCE_USERNAME="orderpilot"
$env:SPRING_DATASOURCE_PASSWORD="orderpilot_dev_password"
mvn spring-boot:run
```

Health check:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/health"
Invoke-RestMethod "http://localhost:8080/actuator/health"
```

Flyway runs on backend startup. To inspect migration history:

```powershell
docker exec -it orderpilot-postgres psql -U orderpilot -d orderpilot -c "select installed_rank, version, description, success from flyway_schema_history order by installed_rank;"
```

## Frontend

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm install
npm run build
npm run dev
```

If PowerShell blocks `npm`, use:

```powershell
npm.cmd install
npm.cmd run build
npm.cmd run dev
```

## AI Worker

The worker runs in mock/advisory mode and does not need real AI provider keys.

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[dev]"
python -m pytest
python -m orderpilot_ai_worker.main
```

If `py` is unavailable but Python 3.12 is on PATH, use `python` instead.

## Stage 2 Demo Seed Data

After the backend is running, load Core v1 demo data through the API:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
.\scripts\seed-demo-data\seed-core-v1.ps1
```

The script creates or reuses a demo tenant, imports CSV fixtures from `packages\test-fixtures\stage2-demo`, validates staged rows, activates valid imports, and prints the demo `tenant_id`. Use that value as `NEXT_PUBLIC_DEMO_TENANT_ID` for dashboard pages that read seeded data. See `docs/runbooks/demo-seed-data.md`.

## Stage 3 Intake Local Testing

After backend startup and demo tenant creation, use `docs\runbooks\intake-local-testing.md` to exercise file upload, API upload, email webhook stubs, Telegram webhook stubs, inbound event reads, and processing job reads.

The frontend intake pages read through core-api using `NEXT_PUBLIC_CORE_API_URL` and `NEXT_PUBLIC_DEMO_TENANT_ID`; they never access the database directly.

## Tests

Run focused foundation checks when changing Stage 1 surfaces:

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn -Dtest=HealthControllerTest,GlobalExceptionHandlerTest,Stage1MigrationFileTest test

cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm.cmd run lint
npm.cmd run build

cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
python -m pytest tests/test_process_inbound_document.py
```

Run broader component checks before handing off larger changes:

```powershell
cd "C:\OrderPilot\OrderPilot-Core\apps\core-api"
mvn test

cd "C:\OrderPilot\OrderPilot-Core\apps\web-dashboard"
npm run lint
npm run build
npm test

cd "C:\OrderPilot\OrderPilot-Core\apps\ai-worker"
python -m pytest
```

## One-Command Local Parity Check

After dependencies are installed, run the local parity check from the repo root:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
powershell -ExecutionPolicy Bypass -File ".\scripts\dev\check-local.ps1"
```

For a CI-like frontend dependency check, use clean install mode:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
powershell -ExecutionPolicy Bypass -File ".\scripts\dev\check-local.ps1" -CleanFrontendInstall
```

The script validates:

- Docker CLI and Docker Compose are available.
- `infra/docker/docker-compose.yml` renders with `docker compose config`.
- PostgreSQL and Redis start through Docker Compose.
- Compose service status is visible.
- `orderpilot-postgres` accepts the repo-defined `orderpilot` role and `orderpilot` database.
- Backend tests pass with Maven and `SPRING_PROFILES_ACTIVE=test`.
- Frontend lint, build, and tests pass with npm.
- AI worker tests pass through `apps\ai-worker\.venv\Scripts\python.exe`.

Because this repository may have unrelated in-progress later-stage changes, inspect `git status --short` before interpreting failures. Do not reset or delete uncommitted work as part of local foundation verification.

The check script does not create `.env`, delete Docker volumes, or modify business logic. By default it uses the existing frontend install. With `-CleanFrontendInstall`, it runs `npm ci` before frontend checks to mirror CI dependency installation. If the AI worker `.venv` is missing, it prints the setup commands and fails.

Reminder: Docker-internal PostgreSQL is still `postgres:5432`, but Windows host tools and locally run backend processes should use `localhost:55432` because native Windows PostgreSQL may own `localhost:5432`.

## CI Parity

GitHub Actions uses `.github/workflows/ci.yml` for repository parity. It runs the same component gates as the local parity check:

- Docker Compose config validation for `infra/docker/docker-compose.yml`.
- Backend Maven tests for `apps/core-api` with `SPRING_PROFILES_ACTIVE=test`.
- Frontend install, lint, build, and tests for `apps/web-dashboard`.
- AI worker install and pytest for `apps/ai-worker`.

The local `scripts\dev\check-local.ps1` script is for developer machines because it starts local PostgreSQL and Redis, verifies the Docker container identity, and uses the existing AI worker virtual environment. CI is for clean repository verification and does not require production secrets, real AI provider keys, external paid services, or local Docker volumes.

CI uses Java 21, Node.js 22 LTS, and Python 3.12. Keep the local parity script and CI workflow aligned when changing build tools, package scripts, Docker Compose paths, test commands, or supported runtime versions.

## Troubleshooting: role "postgres" does not exist

This error is expected if you try to connect as the wrong user:

```text
FATAL: role "postgres" does not exist
```

Use the repo-defined role and database instead:

```powershell
docker exec -it orderpilot-postgres psql -U orderpilot -d orderpilot
```

If this still fails after the Compose files were updated, the existing local Docker volume was probably initialized with old credentials. PostgreSQL only uses `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_DB` on first initialization of an empty data directory.

If the mismatch appears again, do not delete the volume manually. Use the reset script below so the data-loss warning is explicit.

## Reset Local Dev Postgres Volume

DATA LOSS WARNING: this deletes the local development PostgreSQL data volume only. It removes local tables and seed data so Postgres can initialize again with the current `orderpilot` role and database.

Interactive reset:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
powershell -ExecutionPolicy Bypass -File ".\scripts\dev\reset-postgres-volume.ps1"
```

Non-interactive reset:

```powershell
cd "C:\OrderPilot\OrderPilot-Core"
powershell -ExecutionPolicy Bypass -File ".\scripts\dev\reset-postgres-volume.ps1" -Force
```

Then verify:

```powershell
docker compose -f "infra/docker/docker-compose.yml" ps
docker exec -it orderpilot-postgres psql -U orderpilot -d orderpilot
```
