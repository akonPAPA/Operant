# Local Demo Runbook

## Goal

Run the OrderPilot Core v1 investor demo locally without fragile manual steps.

The local demo does not perform real Telegram API calls, real LLM calls, ERP writes, payment integrations, production auto-seeding, or production data mutation.

## Windows Stage 9I Baseline Flow

Launch Docker Desktop first, then start only the repo-defined local infrastructure:

```powershell
cd C:\OrderPilot\OrderPilot-Core\infra\docker
docker compose up -d postgres redis
docker compose ps
```

Expected local infrastructure:

- `postgres` healthy on `localhost:5432`
- `redis` healthy on `localhost:6379`

Do not run `docker compose down -v` for normal demo recovery. The local Postgres volume preserves demo data between restarts.

Return to the repository root, seed deterministic local demo data, start the demo, and verify:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File .\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
powershell -ExecutionPolicy Bypass -File .\scripts\check-no-secrets.ps1
```

Native `psql` is optional for the standard localhost demo. If `psql` is not on PATH, `seed-local-demo.ps1` uses the repo-defined Docker Compose `postgres` service as a safe fallback for localhost datasource seeding.

## One-Command Helpers

From the repository root:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\start-local-demo.ps1
```

The start script opens backend and frontend processes when their local endpoints are not already responding. It does not use production secrets. It fails before starting if Java, Maven, Node, npm, `node_modules`, `.env.local`, or local Postgres are not ready.

Seed local demo data after Flyway migrations have created the schema:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
```

The seed script uses deterministic demo UUIDs only:

- tenant: `11111111-1111-4111-8111-111111111111`
- product: `44444444-4444-4444-8444-444444444444`
- location: `33333333-3333-4333-8333-333333333333`

It uses local `psql` when available. If native `psql` is unavailable, it falls back to `docker compose exec -T postgres psql` through `infra\docker\docker-compose.yml` for localhost datasource seeding. It does not call Telegram, LLMs, ERP, payment systems, or production seeders.

Check the running demo:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-local-demo.ps1
```

The check script fails clearly when Java, Maven, Node, npm, `node_modules`, `.env.local`, seeded demo IDs, Postgres, backend health, frontend runtime, or key routes are not ready.

For fixture-only visual checks without seeded IDs:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-local-demo.ps1 -AllowFixtureMode
```

## Backend Runtime Prerequisites

Backend build/test success and backend runtime readiness are different checks:

- `mvn clean test` uses the test profile and an in-memory H2 database. It proves the backend code and tests pass.
- `mvn spring-boot:run` uses normal runtime configuration. It requires reachable local PostgreSQL.
- `npm run build` proves the frontend compiles and renders static routes.
- Full demo readiness requires backend runtime on `localhost:8080`, frontend runtime on `localhost:3000`, local Postgres reachable, `.env.local` filled with seeded demo IDs, and `check-local-demo.ps1` passing.

Runtime database variables:

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/orderpilot"
$env:SPRING_DATASOURCE_USERNAME="orderpilot_app"
$env:SPRING_DATASOURCE_PASSWORD="<local-postgres-placeholder>"
$env:SERVER_PORT="8080"
```

`SERVER_PORT` is optional when using the default `8080`.

Verify Postgres TCP reachability before starting the backend:

```powershell
Test-NetConnection localhost -Port 5432
```

If this fails, start local Postgres or correct `SPRING_DATASOURCE_URL`. Do not treat passing backend tests as proof that runtime Postgres is ready.

## Backend Commands

Verify backend tests:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn clean test
```

Start backend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn spring-boot:run
```

Expected health endpoint:

`http://localhost:8080/api/v1/health`

## Frontend Commands

Install dependencies:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm install
```

Run frontend checks:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm run lint
npm run typecheck
npm run build
npm test
```

Start frontend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm run dev
```

If PowerShell blocks `npm.ps1`, use `npm.cmd` with the same arguments.

Open:

`http://localhost:3000/demo`

## Environment Setup

Copy the frontend example:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
Copy-Item .env.local.example .env.local
```

Set browser-visible demo values in `.env.local`:

```dotenv
NEXT_PUBLIC_CORE_API_URL=http://localhost:8080
NEXT_PUBLIC_DEMO_TENANT_ID=11111111-1111-4111-8111-111111111111
NEXT_PUBLIC_DEMO_PRODUCT_ID=44444444-4444-4444-8444-444444444444
NEXT_PUBLIC_DEMO_LOCATION_ID=33333333-3333-4333-8333-333333333333
```

Use seeded demo UUIDs for a full button-driven backend flow. Do not put secrets in `NEXT_PUBLIC_*`; these values are visible in the browser. Local `.env.local` files are gitignored.

## Demo Buttons

Use the `/demo` buttons in this order:

1. Send demo Telegram RFQ.
2. Send unknown message.
3. Run inventory reconciliation.
4. View reconciliation cases.
5. Refresh analytics.

If seeded IDs are missing, the UI keeps showing clearly labeled demo fixtures and explains that backend data is not seeded yet. Use `docs/investor/demo-api-walkthrough.http` for request-level walkthroughs.

## Expected Visible Content

The `/demo` page should show:

- `OrderPilot Demo Parts Distributor`.
- `B2B auto and industrial parts distributor`.
- Timeline from Telegram RFQ intake through audit/security.
- KPI cards for bot RFQs, human handoffs, reconciliation cases, high severity cases, Telegram count, and demo status.
- Telegram RFQ text: `Need brake pads for Toyota Camry 2018, 20 pcs, wholesale, Almaty.`
- Reconciliation values: opening `150`, sold `34`, expected `116`, actual `100`, mismatch `-16`, severity `HIGH`.
- Analytics note that total sales amount is `0` when invoice/sales mirror records are not present.
- Security/trust panel stating that the bot cannot approve quotes, create final orders, update inventory/prices/customers, or write to ERP.

Key routes checked by `check-local-demo.ps1`:

- `/demo`
- `/command-center`
- `/inbox`
- `/bot-conversations`
- `/bot/conversations`
- `/reconciliation`
- `/analytics`
- `/audit-log`
- `/integrations`

The alias `/bot/conversations` redirects to `/bot-conversations` and is checked as part of the route probe.

## Visual Verification

Use `docs/investor/DEMO_SCREENSHOT_CHECKLIST.md` for screenshot coverage.

Because the in-app browser connector previously hit a local `EPERM` startup issue, the supported fallback is:

1. Run `check-local-demo.ps1`.
2. Open `http://localhost:3000/demo` in a normal browser.
3. Capture the checklist views manually.

Do not commit large screenshot binaries unless they are explicitly part of the demo evidence package.

## Troubleshooting

### Backend Not Running

Symptom: `check-local-demo.ps1` fails `Core API health`.

Fix:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn spring-boot:run
```

Then re-run:

```powershell
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-local-demo.ps1
```

### Frontend Port Busy

Symptom: `npm run dev` cannot bind port `3000`.

Fix: stop the existing frontend process or start Next on another port:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm run dev -- -p 3001
```

Then check with:

```powershell
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-local-demo.ps1 -FrontendUrl http://localhost:3001
```

### EPERM Browser Startup Issue

Symptom: automated in-app browser startup fails before opening `/demo`.

Fix: use the HTTP route probe plus a normal desktop browser. This is a local tooling issue, not a demo route failure, if `check-local-demo.ps1` passes.

### Missing Demo Product or Location IDs

Symptom: the reconciliation button says demo backend data is not seeded yet.

Fix: seed local demo data and set seeded values in `apps/web-dashboard/.env.local`:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
```

Expected values:

```dotenv
NEXT_PUBLIC_DEMO_TENANT_ID=11111111-1111-4111-8111-111111111111
NEXT_PUBLIC_DEMO_PRODUCT_ID=44444444-4444-4444-8444-444444444444
NEXT_PUBLIC_DEMO_LOCATION_ID=33333333-3333-4333-8333-333333333333
```

Restart `npm run dev` after changing `.env.local`.

### Missing PostgreSQL Client or Service

Symptom: `seed-local-demo.ps1` reports that `psql` is unavailable, or startup/check scripts report that Postgres is unreachable at `localhost:5432`.

Preferred Docker Desktop fix:

```powershell
cd C:\OrderPilot\OrderPilot-Core\infra\docker
docker compose up -d postgres redis
docker compose ps
```

Then:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File .\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
```

Native PostgreSQL fix, only if you choose not to use Docker Desktop:

1. Install or start local PostgreSQL.
2. Create database `orderpilot` and user `orderpilot_app`.
3. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and the local database credential in the shell that starts core-api.
4. Start core-api once so Flyway applies migrations.
5. Run `scripts\seed-local-demo.ps1 -UpdateFrontendEnv`.

### CORS or API Base URL Issues

Symptom: browser calls fail while the backend health endpoint works in PowerShell.

Fix:

- Confirm `NEXT_PUBLIC_CORE_API_URL=http://localhost:8080`.
- Confirm the frontend was restarted after `.env.local` changed.
- Confirm the browser is opening the expected frontend port.
- If CORS is added later, keep it local-demo scoped and do not relax production origins.

### NPM Vulnerabilities Note

`npm install` currently reports frontend vulnerabilities in the existing Next.js dependency chain. Do not run `npm audit fix --force` during the demo polish pass.

See:

`docs/runbooks/DEPENDENCY_AUDIT_NOTES.md`

Any dependency upgrade should be targeted and followed by:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm run lint
npm run typecheck
npm run build
npm test
```
