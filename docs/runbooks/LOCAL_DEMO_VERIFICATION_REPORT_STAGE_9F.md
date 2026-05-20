# Local Demo Verification Report - Stage 9F.1

## Metadata

- Date/time: 2026-05-18T21:10:28+05:00
- Verifier: Codex
- Commit hash: `918c9a4`
- Branch: `main`
- Scope: PostgreSQL Runtime Unblock & Seed Verification for the local investor demo.

## Stage 9F.1 Goal

Unblock the full local backend runtime by selecting an available PostgreSQL runtime path, verifying Flyway migrations against local PostgreSQL, running the deterministic demo seed helper, and rerunning the local demo checks.

No backend domain logic, demo UI redesign, new features, Playwright dependency, real secrets, or forced npm audit upgrades were added in this pass.

## Selected PostgreSQL Path

| Item | Result |
| --- | --- |
| Selected path | BLOCKED: Docker remains the preferred local path, but Docker is not installed or not available on PATH in this shell. Native PostgreSQL is also not installed or not available on PATH. |
| Docker availability | FAIL: `docker --version` and `docker compose version` both returned `docker is not recognized`. |
| Native PostgreSQL availability | FAIL: `psql --version` returned `psql is not recognized`. |
| PostgreSQL service detection | FAIL: no Windows service matching PostgreSQL was found. |
| PostgreSQL install path detection | FAIL: `C:\Program Files\PostgreSQL` was not present. |
| PostgreSQL TCP check | FAIL: `Test-NetConnection localhost -Port 5432` reported `TcpTestSucceeded: False`. |
| PostgreSQL version | Unavailable: no Docker or native PostgreSQL runtime was available to query. |
| `psql` availability | Unavailable on PATH. |
| Database name | Intended local demo database: `orderpilot`. Not created in this session because no PostgreSQL runtime/tooling was available. |

## Preferred Docker Path

The repository already contains a demo-safe Docker compose service at `infra\docker\docker-compose.yml`:

- image: `postgres:16-alpine`
- database: `orderpilot`
- user: `orderpilot_app`
- password: demo-safe local value from your local shell or compose environment; do not commit real credentials.
- port: `localhost:5432`

Once Docker Desktop is installed and available on PATH, use:

```powershell
cd C:\OrderPilot\OrderPilot-Core\infra\docker
docker compose up -d postgres
docker compose ps postgres
```

Verify from PowerShell:

```powershell
Test-NetConnection localhost -Port 5432
docker compose exec postgres psql -U orderpilot_app -d orderpilot -c "select version();"
```

Use only demo-safe local credentials. Do not put production credentials in `.env`, `.env.local`, shell history, or committed files.

## Native PostgreSQL Fallback Path

If using native PostgreSQL instead of Docker:

1. Install PostgreSQL 16+ with command-line tools.
2. Ensure the PostgreSQL service is running.
3. Ensure `psql` is on PATH.
4. Create the demo database and user with demo-safe local credentials only.

Example commands after opening a shell where `psql` is available:

```powershell
psql -U postgres -c "CREATE USER orderpilot_app WITH PASSWORD '<demo-local-password>';"
psql -U postgres -c "CREATE DATABASE orderpilot OWNER orderpilot_app;"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE orderpilot TO orderpilot_app;"

$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/orderpilot"
$env:SPRING_DATASOURCE_USERNAME="orderpilot_app"

Test-NetConnection localhost -Port 5432
psql -h localhost -p 5432 -U orderpilot_app -d orderpilot -c "select version();"
```

Set `SPRING_DATASOURCE_PASSWORD` in the same shell to the matching demo-local PostgreSQL password before starting core-api. Do not commit the value.

If the user already exists, adjust with `ALTER USER orderpilot_app WITH PASSWORD '<demo-local-password>';` rather than creating a second user.

## Backend DB Verification

| Check | Result | Evidence |
| --- | --- | --- |
| Start core-api | BLOCKED | `scripts\start-local-demo.ps1` stopped before launch because PostgreSQL was unreachable at `localhost:5432`. |
| Flyway migration execution | BLOCKED | Flyway could not run because backend runtime could not connect to PostgreSQL. |
| Backend health endpoint | BLOCKED | `http://localhost:8080/api/v1/health` was unreachable because core-api was not started. |
| Backend demo data read after seed | BLOCKED | Seed could not run because `psql` is unavailable on PATH. |

Backend runtime config expected by `apps\core-api\src\main\resources\application.yml`:

```properties
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/orderpilot
SPRING_DATASOURCE_USERNAME=orderpilot_app
```

Set `SPRING_DATASOURCE_PASSWORD` locally before starting the backend; do not write the credential into committed files.

## Demo Seed Verification

Command run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\seed-local-demo.ps1 -UpdateFrontendEnv
```

Result: BLOCKED.

Evidence:

- The script stopped with `psql is unavailable on PATH`.
- No seed SQL was applied.
- No database rows were verified.

Seeded IDs expected by `scripts\seed-local-demo.ps1` and currently present in `apps\web-dashboard\.env.local`:

| Value | Expected | Current frontend env | Result |
| --- | --- | --- | --- |
| Tenant ID | `11111111-1111-4111-8111-111111111111` | `11111111-1111-4111-8111-111111111111` | MATCH |
| Product ID | `44444444-4444-4444-8444-444444444444` | `44444444-4444-4444-8444-444444444444` | MATCH |
| Location ID | `33333333-3333-4333-8333-333333333333` | `33333333-3333-4333-8333-333333333333` | MATCH |

No local secrets were committed.

## Runtime Checks

### `scripts\start-local-demo.ps1`

Command run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-local-demo.ps1
```

Result: BLOCKED.

Evidence:

- Java, Maven, Node, and npm were found.
- Frontend `node_modules` was present.
- `apps\web-dashboard\.env.local` contained required demo keys.
- Backend port `8080` and frontend port `3000` were free.
- The script stopped at `Postgres is unreachable at localhost:5432`.
- No backend or frontend process was started.

### `scripts\check-local-demo.ps1 -AllowFixtureMode`

Command run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\check-local-demo.ps1 -AllowFixtureMode
```

Result: FAIL.

Evidence:

- Required tools Java, Maven, Node, and npm were found.
- Required repo paths were found.
- Frontend dependencies and `.env.local` were found.
- Demo IDs were configured and non-placeholder.
- PostgreSQL was unreachable at `localhost:5432`.
- Backend was not listening on `localhost:8080`.
- Frontend was not listening on `localhost:3000`.
- Core API health probe failed.
- Demo API probes failed because the backend was not running.
- Dashboard route probes failed because the frontend was not running.
- Total reported issues: 17.

## Backend Runtime Status

| Item | Result |
| --- | --- |
| Runtime process | BLOCKED: not started because PostgreSQL was unreachable. |
| Health endpoint | BLOCKED: `localhost:8080` was not listening. |
| Flyway migrations | BLOCKED: no PostgreSQL connection available. |
| Demo API probes | BLOCKED: backend runtime unavailable. |

## Frontend Runtime Status

| Item | Result |
| --- | --- |
| Runtime process | NOT STARTED by `start-local-demo.ps1` because the script stops before launching services when PostgreSQL is unavailable. |
| Port 3000 | Not listening during the full local demo check. |
| Route probes | FAIL in the full local demo check because the frontend runtime was not started. |
| Demo env IDs | CONFIGURED and match the deterministic seed helper values. |

## Manual Install Prerequisite

Stage 9F.1 cannot complete on this machine until one of these external prerequisites is installed and available on PATH:

- Docker Desktop with `docker` and `docker compose`, preferred because the repo already has `infra\docker\docker-compose.yml`; or
- Native PostgreSQL 16+ with `psql` available on PATH and a running local service.

After installing one path, rerun in this order:

```powershell
cd C:\OrderPilot\OrderPilot-Core

# Docker preferred path:
cd C:\OrderPilot\OrderPilot-Core\infra\docker
docker compose up -d postgres
Test-NetConnection localhost -Port 5432
docker compose exec postgres psql -U orderpilot_app -d orderpilot -c "select version();"

# Back to repo root:
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File scripts\start-local-demo.ps1
powershell -ExecutionPolicy Bypass -File scripts\seed-local-demo.ps1 -UpdateFrontendEnv
powershell -ExecutionPolicy Bypass -File scripts\check-local-demo.ps1 -AllowFixtureMode
```

If using native PostgreSQL, create `orderpilot` and `orderpilot_app` first, set `SPRING_DATASOURCE_*` in the same shell used to start core-api, then run the same seed and check scripts.

## Final Status

`PASS_WITH_LIMITATIONS`

Rationale: Stage 9F.1 detection, script execution, seed ID comparison, and verification reporting were completed. The remaining blocker is a clearly external local prerequisite: neither Docker nor native PostgreSQL/`psql` is installed or available on PATH, and no PostgreSQL service is listening on `localhost:5432`. Because PostgreSQL is unavailable, backend live runtime, Flyway migration execution, deterministic seed application, and full route/API probes remain blocked.
