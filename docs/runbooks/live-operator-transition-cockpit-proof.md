# Live operator transition proof — RFQ handoff cockpit (PR #262)

This runbook is a product-proof + safety-proof walkthrough for the demo RFQ-to-quote operator cockpit path.

It is intentionally scoped to a **safe demo terminal**. It must not execute ERP/1C/accounting writes, connector execution, ChangeRequest execution, outbox external sends, or autonomous AI approval.

## 1. Prerequisites

- Windows PowerShell
- Java 21 + Maven
- Node.js/npm
- Docker Desktop with Docker Compose
- Ports available: `15432`, `8080`, `3000`

## 2. Local stack startup

### 2.1 Start disposable local PostgreSQL

From the repository root:

```powershell
docker compose -f infra\docker\docker-compose.yml up -d postgres
docker compose -f infra\docker\docker-compose.yml ps postgres
```

Create a dedicated disposable proof database:

```powershell
docker exec orderpilot-postgres dropdb --if-exists -U orderpilot_local_user operant_live_operator_proof
docker exec orderpilot-postgres createdb -U orderpilot_local_user operant_live_operator_proof
```

## 3. Seed/reset demo state

In a PowerShell session:

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:15432/operant_live_operator_proof"
$env:SPRING_DATASOURCE_USERNAME = "<local-demo-db-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<local-demo-db-password>"
.\scripts\seed-local-demo.ps1
```

Notes:

- The seed must be local-only and disposable.
- The seed must not call Telegram, an LLM, ERP/1C, accounting, or any connector.

## 4. Backend startup (core-api)

In a PowerShell session:

```powershell
$env:SPRING_PROFILES_ACTIVE = "demo"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:15432/operant_live_operator_proof"
$env:SPRING_DATASOURCE_USERNAME = "<local-demo-db-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<local-demo-db-password>"
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_ENABLED = "true"
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_SIGNATURE_REQUIRED = "false"
$env:ORDERPILOT_CORS_ALLOWED_ORIGINS = "http://localhost:3000"
$env:ORDERPILOT_ACTOR_SIGNING_SECRET = ""
$env:ORDERPILOT_DEMO_RFQ_HANDOFF_ENABLED = "true"

mvn -f apps\core-api\pom.xml spring-boot:run
```

Expected evidence:

- Flyway validates/applies migrations.
- `GET http://localhost:8080/api/v1/health` returns `200`.

## 5. Frontend startup (web-dashboard)

In a PowerShell session:

```powershell
$env:CORE_API_BASE_URL = "http://localhost:8080"
$env:ORDERPILOT_DEMO_MODE = "true"
$env:ORDERPILOT_DEMO_TENANT_ID = "11111111-1111-4111-8111-111111111111"
$env:NEXT_PUBLIC_CORE_API_URL = "http://localhost:8080"
$env:NEXT_PUBLIC_ORDERPILOT_DEMO_MODE = "true"
$env:NEXT_PUBLIC_DEMO_TENANT_ID = "11111111-1111-4111-8111-111111111111"
$env:NEXT_PUBLIC_DEMO_PRODUCT_ID = "44444444-4444-4444-8444-444444444444"
$env:NEXT_PUBLIC_DEMO_LOCATION_ID = "33333333-3333-4333-8333-333333333333"

npm --prefix apps\web-dashboard run dev
```

## 6. Exact browser proof path (manual)

No Playwright/Cypress E2E harness is currently assumed for this repository. This proof is therefore a deterministic manual walkthrough.

### 6.1 `/demo` -> create demo RFQ handoff

1. Open `http://localhost:3000/demo`.
2. Click **Send demo Telegram RFQ** twice (replay proof).

Expected:

- The request succeeds.
- The UI does not render `tenantId`, `actorId`, idempotency keys, raw payload JSON, prompts, stack traces, connector credentials, or internal-only IDs.

### 6.2 `/channels/rfq-handoffs` -> open handoff -> start operator review

1. Open `http://localhost:3000/channels/rfq-handoffs`.
2. Confirm **exactly one** tenant-scoped pending RFQ handoff is visible for the demo request.
3. Open the handoff.
4. Click **Start review**.

### 6.3 Generate AI advisory (advisory-only)

1. Click **Generate suggestion**.

Expected:

- The advisory is explicitly marked **advisory only**.
- The cockpit “next operator action” remains a human/operator step, not an autonomous approval/execution.

### 6.4 Create review-required draft quote

1. Click **Create draft quote**.

Expected:

- Draft quote shows **review-required** posture (not customer-approved).
- External execution remains **DISABLED**.
- Connector action remains **NOT_INVOKED** (or equivalent honest “not executed” token).
- Outbox remains **NOT_REQUESTED** (or equivalent).

### 6.5 Terminal safe demo decision

1. Enter a decision note (required).
2. Click **Complete safe demo** (or the safe decline terminal, if testing the alternate path).

Expected:

- Terminal state reaches a safe demo terminal (e.g. `SAFE_DEMO_TERMINAL`).
- No customer-send action occurs.
- No external execution occurs.

### 6.6 Visibility coherence

1. Confirm `/channels/rfq-handoffs` cockpit reflects the terminal state and shows a coherent “next operator action” (terminal/no-op).
2. Open:
   - `http://localhost:3000/commerce-intelligence`
   - `http://localhost:3000/runtime-control`

Expected:

- Both surfaces remain **read-only** and consistent with `externalExecution=DISABLED`.

## 7. Database invariants (PostgreSQL queries)

Run these queries against the disposable proof DB `operant_live_operator_proof`.

### 7.1 Positive expected state

Expected after the walkthrough:

- exactly one tenant-scoped RFQ handoff for the seeded/demo message
- zero duplicate handoffs from replay
- advisory exists if generated
- draft quote exists if created
- terminal decision exists if executed
- audit event exists for each operator transition
- idempotency replay does not duplicate state

### 7.2 Negative forbidden side effects

Expected after the walkthrough:

- zero connector executions
- zero external ERP/1C/accounting writes
- zero ChangeRequest executions
- zero outbox external send rows
- zero customer-send execution rows
- zero autonomous AI approval state

Concrete table names used by existing proof runbooks (confirm in schema if they change):

- `connector_command`
- `connector_sandbox_execution`
- `change_request`
- `outbox_event`

## 8. Troubleshooting

- If `/demo` fails with a safe 503, confirm the **server-only** demo env vars are set (`ORDERPILOT_DEMO_MODE`, `ORDERPILOT_DEMO_TENANT_ID`).
- If the handoff is missing, confirm core-api is running in `demo` profile with `ORDERPILOT_DEMO_RFQ_HANDOFF_ENABLED=true`.
- If PostgreSQL queries fail, confirm Docker container `orderpilot-postgres` is healthy and the DB name is correct.

## 9. What remains not proven (must stay honest)

- Fully automated browser click-through proof (no Playwright/Cypress harness assumed).
- Screenshot capture proof (only if not attached/executed).
- Snyk baseline cleanliness (requires Snyk run evidence and/or remediation).

