# Investor Demo Handoff

## Demo Purpose

The OrderPilot Core v1 investor demo shows how a B2B auto and industrial parts distributor can turn messy Telegram, PDF, and Excel-style requests into reviewed drafts, safe handoffs, reconciliation signals, analytics, and an audit/security story.

The demo is local and controlled. It does not call real Telegram APIs, real LLM APIs, ERP systems, payment integrations, or production seeders.

Demo URL:

`http://localhost:3000/demo`

## Required Services

- `core-api` on `http://localhost:8080`
- `web-dashboard` on `http://localhost:3000`
- local PostgreSQL reachable by the backend runtime

Backend tests use an H2 test profile; full runtime uses local PostgreSQL.

## Startup Sequence

1. Verify backend tests:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn clean test
```

2. Configure backend runtime database variables if needed:

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/orderpilot"
$env:SPRING_DATASOURCE_USERNAME="orderpilot_app"
$env:SPRING_DATASOURCE_PASSWORD="<local-postgres-placeholder>"
```

3. Verify Postgres:

```powershell
Test-NetConnection localhost -Port 5432
```

4. Start backend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn spring-boot:run
```

5. Configure frontend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
Copy-Item .env.local.example .env.local
```

Keep `NEXT_PUBLIC_ORDERPILOT_DEMO_MODE=true` for this local-only workflow, then fill
`NEXT_PUBLIC_DEMO_TENANT_ID`, `NEXT_PUBLIC_DEMO_PRODUCT_ID`, and
`NEXT_PUBLIC_DEMO_LOCATION_ID` with seeded local demo UUIDs. Production rejects this demo mode.

6. Start frontend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm install
npm run dev
```

Helper script:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\start-local-demo.ps1
```

## Check Sequence

Run:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-local-demo.ps1
```

The check verifies local tools, npm dependencies, `.env.local`, seeded demo IDs, Postgres TCP reachability, backend health, and key frontend routes.

For fixture-only visual review without seeded IDs:

```powershell
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-local-demo.ps1 -AllowFixtureMode
```

## Demo Button Flow

Use `/demo` in this order:

1. Send demo Telegram RFQ.
2. Send unknown message.
3. Run inventory reconciliation.
4. View reconciliation cases.
5. Refresh analytics.

Expected demo RFQ:

`Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.`

Expected reconciliation fixture:

- Opening stock: `150`
- Sold: `34`
- Expected stock: `116`
- Actual stock: `100`
- Mismatch: `-16`
- Severity: `HIGH`

## Screenshot Checklist

Use:

`docs/investor/DEMO_SCREENSHOT_CHECKLIST.md`

Visual verification is manual. No Playwright dependency was added.

## Route Checklist

These routes must respond in the local demo:

- `/demo`
- `/command-center`
- `/inbox`
- `/bot-conversations`
- `/bot/conversations`
- `/reconciliation`
- `/analytics`
- `/audit-log`
- `/integrations`

## Known Limitations

- No Playwright dependency was added.
- Visual verification is manual.
- Backend runtime needs local PostgreSQL even though backend tests use H2.
- `npm audit` has documented known vulnerabilities in the current frontend dependency chain.
- No `npm audit fix --force` was applied.
- Full button-driven reconciliation requires seeded demo tenant, product, and location IDs in `apps/web-dashboard/.env.local`.

## Reference Docs

- `docs/runbooks/LOCAL_DEMO_RUNBOOK.md`
- `docs/investor/DEMO_SCREENSHOT_CHECKLIST.md`
- `docs/runbooks/DEPENDENCY_AUDIT_NOTES.md`
- `docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md`
- `docs/investor/demo-api-walkthrough.http`
- `docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_TEMPLATE.md`
