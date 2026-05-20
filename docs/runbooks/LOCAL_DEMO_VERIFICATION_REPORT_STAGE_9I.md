# Local Demo Verification Report - Stage 9I

## Metadata

- Date/time: 2026-05-19T15:20:27.2894718+05:00
- Verifier: Codex
- Active repository root: `C:\OrderPilot\OrderPilot-Core`
- Purpose: stable local demo baseline after Docker Desktop reinstall and Stage 9H schema/entity compatibility fix.

## Environment Assumptions

- Windows host using PowerShell.
- Docker Desktop is installed and must be launched before Compose operations if the daemon is asleep.
- Local demo infrastructure uses the repo-defined Compose file at `infra\docker\docker-compose.yml`.
- Local Postgres data is preserved in the existing Docker volume; no volume deletion was performed.
- Native `psql` is not on PATH, so `seed-local-demo.ps1` uses its safe Docker Compose psql fallback for localhost datasource seeding.
- Backend and frontend may already be running; `start-local-demo.ps1` verifies expected endpoints before starting new processes.

## Repository State

`git status --short --branch` showed a dirty working tree before this Stage 9I pass. This was expected from the existing Stage 7-9 work and Stage 9G/9H recovery files.

Stage 9I only updated documentation/runbook baseline files and reran verification. No product feature, backend domain behavior, AI logic, investor demo UI, Playwright dependency, npm audit state, real secret, Hibernate validation bypass, or Docker volume reset was introduced.

Focused Stage 9I changed files:

- `README.md`
- `docs\runbooks\LOCAL_DEMO_RUNBOOK.md`
- `docs\runbooks\local-development.md`
- `docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9I.md`

Baseline files already changed by prior Stage 9G/9H work and still part of the local demo baseline:

- `scripts\seed-local-demo.ps1`
- `docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9G.md`
- `docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9H.md`
- `apps\core-api\src\main\java\com\orderpilot\domain\customer\CustomerAccount.java`
- `apps\core-api\src\main\java\com\orderpilot\domain\product\Product.java`
- `apps\core-api\src\main\java\com\orderpilot\domain\pricing\PriceRule.java`
- `apps\core-api\src\main\java\com\orderpilot\domain\extraction\ExtractedLineItem.java`

## Docker Status

Docker version:

```text
Docker version 29.4.3, build 055a478
```

Docker Compose version:

```text
Docker Compose version v5.1.3
```

Initial `docker compose ps` failed while Docker Desktop daemon was unavailable:

```text
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
```

Docker Desktop was launched, then only the repo-defined infrastructure services were started:

```powershell
cd C:\OrderPilot\OrderPilot-Core\infra\docker
docker compose up -d postgres redis
```

Final Compose service status:

```text
NAME                IMAGE                SERVICE    STATUS                   PORTS
docker-postgres-1   postgres:16-alpine   postgres   Up 2 minutes (healthy)   0.0.0.0:5432->5432/tcp, [::]:5432->5432/tcp
docker-redis-1      redis:7-alpine       redis      Up 2 minutes (healthy)   0.0.0.0:6379->6379/tcp, [::]:6379->6379/tcp
```

## psql Status and Fallback

Native `psql` status:

```text
psql : The term 'psql' is not recognized
```

Fallback status: PASS. `seed-local-demo.ps1` used the repo-defined Docker Compose `postgres` service:

```text
WARN: psql is unavailable on PATH. Using repo-defined Docker Compose postgres service for local demo seeding.
```

No native PostgreSQL client installation is required for this localhost demo baseline.

## Verification Commands and Results

### Seed

Command:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
```

Result: PASS.

Evidence:

```text
BEGIN
INSERT 0 1
INSERT 0 1
INSERT 0 1
INSERT 0 3
INSERT 0 0
INSERT 0 0
INSERT 0 0
INSERT 0 0
COMMIT
OK: Wrote demo-safe frontend env to C:\OrderPilot\OrderPilot-Core\apps\web-dashboard\.env.local
OK: Local demo seed is present.
Demo tenant id:   11111111-1111-4111-8111-111111111111
Demo product id:  44444444-4444-4444-8444-444444444444
Demo location id: 33333333-3333-4333-8333-333333333333
```

The zero-row inserts are expected for idempotent movement rows already present from prior seeding.

### Start

Command:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1
```

Result: PASS.

Evidence:

```text
WARN: Backend port 8080 is already listening (PID 9764). The script will verify the expected endpoint before starting a new process.
WARN: Frontend port 3000 is already listening (PID 8064). The script will verify the expected endpoint before starting a new process.
OK: Postgres TCP endpoint is reachable at localhost:5432.
Core API already responds at http://localhost:8080/api/v1/health
Web dashboard already responds at http://localhost:3000/demo
```

The script safely reused existing healthy backend/frontend processes and did not start duplicate conflicting processes.

### Full Local Demo Check

Command:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
```

Result: PASS.

Passing probes:

- Java, Maven, Node, and npm found.
- Required repo paths found.
- Frontend `node_modules` found.
- `apps\web-dashboard\.env.local` found.
- Demo tenant/product/location IDs configured.
- Postgres reachable at `localhost:5432`.
- Next.js build output found.
- Backend port `8080` listening.
- Frontend port `3000` listening.
- Core API health returned HTTP 200.
- Demo Telegram RFQ webhook returned HTTP 200.
- Demo inventory reconciliation run returned HTTP 200.
- Demo reconciliation cases returned HTTP 200.
- Demo commerce analytics summary returned HTTP 200.
- Dashboard routes returned HTTP 200:
  - `/demo`
  - `/command-center`
  - `/inbox`
  - `/bot-conversations`
  - `/bot/conversations`
  - `/reconciliation`
  - `/analytics`
  - `/audit-log`
  - `/integrations`

### Secret Scan

Command:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-no-secrets.ps1
```

Result: PASS.

Evidence:

```text
No obvious hardcoded secrets found.
```

## Manual Browser and Route Checklist

HTTP route probes performed:

```powershell
Invoke-WebRequest -Uri http://localhost:3000/ -UseBasicParsing -TimeoutSec 10
Invoke-WebRequest -Uri http://localhost:3000/demo -UseBasicParsing -TimeoutSec 10
Invoke-WebRequest -Uri http://localhost:8080/api/v1/health -UseBasicParsing -TimeoutSec 10
```

Result: all returned HTTP 200.

Important URLs:

- Web root: `http://localhost:3000/`
- Investor demo: `http://localhost:3000/demo`
- Core API health: `http://localhost:8080/api/v1/health`
- Checked dashboard routes: `/demo`, `/command-center`, `/inbox`, `/bot-conversations`, `/bot/conversations`, `/reconciliation`, `/analytics`, `/audit-log`, `/integrations`

Manual visual capture should follow:

- `docs\investor\DEMO_SCREENSHOT_CHECKLIST.md`

No new route was added.

## Documentation Updates

Updated current Windows flow in:

- `docs\runbooks\LOCAL_DEMO_RUNBOOK.md`
- `docs\runbooks\local-development.md`
- `README.md`

The docs now state:

- launch Docker Desktop first;
- start repo-defined Postgres/Redis via Docker Compose;
- seed with `seed-local-demo.ps1 -UpdateFrontendEnv`;
- start with `start-local-demo.ps1`;
- verify with `check-local-demo.ps1 -AllowFixtureMode`;
- run `check-no-secrets.ps1`;
- native `psql` is optional because the seed script has a safe Docker psql fallback for localhost datasource seeding.

## Known Limitations

- Native `psql` is still not on PATH; this is non-blocking for the current localhost demo baseline.
- Docker Desktop may need to be launched after reboot or if the daemon pipe is unavailable.
- Existing frontend dependency audit notes remain unchanged; no `npm audit fix` was run.
- Visual screenshot capture is still a manual evidence step and should use `docs\investor\DEMO_SCREENSHOT_CHECKLIST.md`.

## Final Status

`PASS`

Stage 9I stable local demo baseline is verified. Docker Compose infrastructure is healthy, seed/start/check/no-secrets commands pass, backend and frontend are responding, and key demo routes/API probes pass.

## Next Recommended Development Stage

Next recommended step: investor demo screenshot/checklist capture using `docs\investor\DEMO_SCREENSHOT_CHECKLIST.md`.

Do not begin new product development until the screenshot evidence package is captured or explicitly skipped.
