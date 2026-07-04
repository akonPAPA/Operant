
# Post-PR239 Real Demo Proof

## 1. Purpose

Prove the PR #239/#241 RFQ demo flow against a real PostgreSQL database and the shipped dashboard:

```text
browser /demo RFQ action
-> server-side demo BFF
-> managed inbound channel event + RFQ handoff
-> advisory AI suggestion
-> operator review
-> review-required draft quote
-> COMPLETE_DEMO or DECLINE_DEMO
-> SAFE_DEMO_TERMINAL
```

This is a safe demo terminal. It is not quote approval and cannot request ERP, 1C, accounting,
connector, ChangeRequest, inventory, pricing, or customer-master execution.

## 2. Preconditions

- Java 21 and Maven.
- Node.js/npm with `apps/web-dashboard/node_modules` installed.
- Docker Desktop with Docker Compose.
- Ports `15432`, `8080`, and `3000` available.
- A local-only database. Never run the seed against production or shared data.

No Playwright or Cypress dependency exists in this repository. Browser proof is therefore a
documented manual walkthrough plus the existing lightweight frontend contract test.

## 3. Required environment variables

Backend PowerShell session:

```powershell
$env:SPRING_PROFILES_ACTIVE = "demo"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:15432/operant_post_pr239_proof"
$env:SPRING_DATASOURCE_USERNAME = "<local-demo-db-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<local-demo-db-password>"
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_ENABLED = "true"
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_SIGNATURE_REQUIRED = "false"
$env:ORDERPILOT_CORS_ALLOWED_ORIGINS = "http://localhost:3000"
$env:ORDERPILOT_ACTOR_SIGNING_SECRET = ""
$env:ORDERPILOT_DEMO_RFQ_HANDOFF_ENABLED = "true"
```

These gateway settings are local/demo-only. Production and signed modes must not use unsigned
gateway headers.

Frontend PowerShell session:

```powershell
$env:CORE_API_BASE_URL = "http://localhost:8080"
$env:ORDERPILOT_DEMO_MODE = "true"
$env:ORDERPILOT_DEMO_TENANT_ID = "11111111-1111-4111-8111-111111111111"
$env:NEXT_PUBLIC_CORE_API_URL = "http://localhost:8080"
$env:NEXT_PUBLIC_ORDERPILOT_DEMO_MODE = "true"
$env:NEXT_PUBLIC_DEMO_TENANT_ID = "11111111-1111-4111-8111-111111111111"
$env:NEXT_PUBLIC_DEMO_PRODUCT_ID = "44444444-4444-4444-8444-444444444444"
$env:NEXT_PUBLIC_DEMO_LOCATION_ID = "33333333-3333-4333-8333-333333333333"
```

`ORDERPILOT_DEMO_MODE` and `ORDERPILOT_DEMO_TENANT_ID` are server-only inputs to the PR #241 BFF.
The browser RFQ action sends no tenant, actor, source, connection, status, audit, provider payload,
or idempotency field. Core resolves the local demo operator and all channel authority.

## 4. Local PostgreSQL startup

From the repository root:

```powershell
docker compose -f infra\docker\docker-compose.yml up -d postgres
docker compose -f infra\docker\docker-compose.yml ps postgres
```

Create a dedicated disposable proof database:

```powershell
docker exec orderpilot-postgres dropdb --if-exists -U orderpilot_local_user operant_post_pr239_proof
docker exec orderpilot-postgres createdb -U orderpilot_local_user operant_post_pr239_proof
```

The reset is scoped to `operant_post_pr239_proof`. Do not substitute another database name without
confirming it is disposable.

## 5. Backend startup

Set the backend environment from section 3, then:

```powershell
mvn -f apps\core-api\pom.xml spring-boot:run
```

Expected startup evidence:

- PostgreSQL 16 is detected.
- Flyway validates and applies all migrations.
- `GET http://localhost:8080/api/v1/health` returns `200`.

## 6. Frontend startup

Set the frontend environment from section 3, then:

```powershell
npm --prefix apps\web-dashboard run dev
```

Open `http://localhost:3000/channels/rfq-handoffs`.

## 7. Seed/demo data

In a third PowerShell session:

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:15432/operant_post_pr239_proof"
$env:SPRING_DATASOURCE_USERNAME = "<local-demo-db-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<local-demo-db-password>"
.\scripts\seed-local-demo.ps1
```

The seed creates a backend-owned local demo operator plus one real tenant-scoped Telegram
`inbound_channel_event` and `channel_rfq_handoff`:

```text
handoff = 99999999-9999-4999-8999-999999999903
status = PENDING_REVIEW
request = Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.
```

The seed does not call Telegram, an LLM, ERP/1C, accounting, or a connector.

## 8. Browser walkthrough

1. Open `http://localhost:3000/demo`.
2. Click **Send demo Telegram RFQ** twice.
3. Open `http://localhost:3000/channels/rfq-handoffs`.
4. Confirm exactly one Telegram request for `PAD-OE-04465` is `PENDING_REVIEW`.
5. Open the pending request and click **Generate suggestion**.
6. Confirm the advisory summary and **Advisory only** marker.
7. Click **Start review**.
8. Click **Create draft quote**.
9. Inspect the quote number, quote/validation status, human-review marker, line, and safety summary.
10. Leave the decision note empty and confirm both terminal actions remain disabled.

The PR #240 seed already contains the same stable provider event and handoff. In that standard
walkthrough, the first browser click enters the managed bridge and deduplicates against the seeded
source, so the workspace remains at exactly one handoff. The connection-only automated test
`RfqHandoffRealDemoPostgresIntegrationTest.browserStartedManagedIntakeIsTenantScopedAndReplaySafeOnPostgres`
is the PostgreSQL creation-from-absence proof case; `LocalDemoRfqIntakeServiceTest` proves the same
path with the normal test database.
9. Enter `Reviewed for local demo; no external action requested`.
10. Click **Complete safe demo**. Use **Decline demo draft** only for the alternate path.
11. Confirm the terminal and safety fields in section 9.
12. Reload the page only after recording evidence; terminal decision state is persisted in PostgreSQL,
    while the current terminal result card itself is component state.

## 9. Expected visible UI states

- Source: `TELEGRAM`.
- RFQ request text/preview is visible.
- AI summary, confidence/risk display, and `Advisory only` are visible.
- Draft quote number, status, validation state, line, and issues are visible.
- A decision note is required.
- **Complete safe demo** and **Decline demo draft** are visible after draft creation.
- Result:
  - `quoteState = DEMO_COMPLETED` or `DEMO_DECLINED`;
  - `terminalState = SAFE_DEMO_TERMINAL`;
  - `auditStatus = RECORDED`;
  - `externalExecution = DISABLED`;
  - `connectorAction = NOT_INVOKED`;
  - `outboxStatus = NOT_REQUESTED`.
- The UI must not display `actorId`, `tenantId`, an idempotency key, raw AI payload, audit event ID,
  connector credentials, or raw backend errors.

## 10. Expected API/backend state

The client sends:

- channel reads: tenant demo context plus `ADMIN_SETTINGS_READ`;
- channel transitions: safe route handle plus `ADMIN_SETTINGS_MANAGE`;
- advisory: safe route handle, `workType`, and `Idempotency-Key`;
- draft creation: safe route handle and an empty business-intent body;
- decision: `decision`, `note`, and `Idempotency-Key`.

The backend resolves tenant, actor, role, handoff, source text, draft association, state transition,
audit, idempotency scope, and safety fields. The handoff ends `CONVERTED`; its draft quote ends
`DEMO_COMPLETED` or `DEMO_DECLINED` with `requiresHumanReview = true`.

## 11. Audit and idempotency behavior

- Exactly one `RFQ_HANDOFF_DEMO_DECISION_RECORDED` event is persisted for the decision.
- Repeating the same decision request with the same `Idempotency-Key` returns the stored response.
- One successful `idempotency_key` row exists for command `RFQ_HANDOFF_DEMO_DECISION`.
- A different terminal transition is rejected and does not change the stable quote state.
- Wrong permission and explicit `SYSTEM_ACTOR` are rejected before idempotency/service mutation.

## 12. External execution safety

Expected fixed result:

```text
externalExecution = DISABLED
connectorAction = NOT_INVOKED
outboxStatus = NOT_REQUESTED
```

Expected tenant-scoped counts after the flow:

```text
connector_command = 0
connector_sandbox_execution = 0
change_request = 0
outbox_event = 0
```

No ERP, 1C, accounting, or connector write is requested.

## 13. PostgreSQL integration proof

Run:

```powershell
mvn -f apps/core-api/pom.xml `
  "-Dspring.profiles.active=integration-test" `
  "-Dorderpilot.postgres.integration.enabled=true" `
  "-Dtest=PostgresMigrationSmokeIntegrationTest,RfqHandoffRealDemoPostgresIntegrationTest" test
```

The explicit `orderpilot.postgres.integration.enabled=true` property is required; without it the
annotated PostgreSQL tests are skipped.

Proof covers:

- Flyway migrations on PostgreSQL;
- tenant-scoped handoff and draft lookups;
- PostgreSQL pessimistic-lock queries used by review/create/decision transactions;
- route-derived draft uniqueness;
- decision idempotency replay;
- cross-tenant and wrong-role denial without mutation;
- stable terminal state and audit exactly once;
- no connector, sandbox, ChangeRequest, compensation, or outbox state.

## 14. Commands run and exact result (2026-07-03/04)

```text
docker compose -f infra\docker\docker-compose.yml up -d postgres
PASS: postgres:16-alpine healthy on localhost:15432

mvn -f apps/core-api/pom.xml "-Dspring.profiles.active=integration-test" \
  "-Dorderpilot.postgres.integration.enabled=true" \
  "-Dtest=RfqHandoffRealDemoPostgresIntegrationTest" test
PASS: 2 tests, 0 failures, 0 errors, 0 skipped
Flyway: 65 migrations validated; PostgreSQL 16.14

mvn -f apps/core-api/pom.xml "-Dspring.profiles.active=integration-test" \
  "-Dorderpilot.postgres.integration.enabled=true" \
  "-Dtest=PostgresMigrationSmokeIntegrationTest,RfqHandoffRealDemoPostgresIntegrationTest" test
PASS: 3 tests, 0 failures, 0 errors, 0 skipped

mvn -f apps/core-api/pom.xml \
  "-Dtest=RfqHandoffDraftQuoteControllerTest,RfqHandoffDraftQuoteServiceTest,RequestActorResolverLocalDemoTest,ChannelRfqHandoffControllerAuthorityBoundaryTest" test
PASS: 29 tests, 0 failures, 0 errors, 0 skipped

mvn -f apps/core-api/pom.xml \
  "-Dtest=AiWorkControllerAuthorityBoundaryTest,RfqHandoffDraftQuoteControllerTest,ChannelRfqHandoffControllerAuthorityBoundaryTest,RequestActorResolverLocalDemoTest" test
PASS: 29 tests, 0 failures, 0 errors, 0 skipped

mvn -f apps/core-api/pom.xml \
  "-Dtest=RfqHandoffDraftQuoteControllerTest,RfqHandoffDraftQuoteServiceTest,RequestActorResolverLocalDemoTest,ChannelRfqHandoffControllerAuthorityBoundaryTest,AiWorkControllerAuthorityBoundaryTest" test
PASS: 37 tests, 0 failures, 0 errors, 0 skipped

live PostgreSQL-backed API flow
PASS: PENDING_REVIEW -> advisory -> IN_REVIEW -> draft NEEDS_REVIEW -> DEMO_COMPLETED
PASS: repeated Idempotency-Key returned the same response
PASS: decision audit count 1; idempotency row count 1/SUCCEEDED
PASS: connector command 0; sandbox execution 0; ChangeRequest 0; outbox 0
PASS: support-only permission 403; explicit SYSTEM_ACTOR 403

node --test apps/web-dashboard/tests/rfq-handoffs.test.mjs
PASS: 26 tests, 0 failures

npm --prefix apps/web-dashboard run lint
PASS

npm --prefix apps/web-dashboard run typecheck
PASS

npm --prefix apps/web-dashboard run build
PASS: compiled, typechecked, and generated 51/51 static pages
```

The first PostgreSQL proof attempt failed because its test fixture used a random actor that violated
the real `audit_event.actor_id -> user_account` foreign key. The fixture was corrected to use the
seeded tenant user; no constraint or production check was weakened.

## 14A. PR #242 browser + PostgreSQL proof (2026-07-04)

Live disposable database `operant_post_pr239_proof` on `localhost:15432` (Docker `postgres:16-alpine`),
Core API on `:8080` (profile `demo`, `orderpilot.demo.rfq-handoff.enabled=true`, unsigned local gateway
header auth), dashboard on `:3000`.

```text
mvn -f apps/core-api/pom.xml "-Dspring.profiles.active=integration-test" \
  "-Dorderpilot.postgres.integration.enabled=true" \
  "-Dtest=PostgresMigrationSmokeIntegrationTest,RfqHandoffRealDemoPostgresIntegrationTest" test
PASS: 4 tests, 0 failures, 0 errors, 0 skipped; Flyway 65 migrations validated; PostgreSQL 16.14

mvn -f apps/core-api/pom.xml "-Dtest=*DemoRfqHandoff*Test,*LocalDemo*Test,*Bot*Test,*RfqHandoff*Test" test
PASS: 138 tests, 0 failures, 3 skipped (the Postgres-only integration tests skip under H2)

mvn -f apps/core-api/pom.xml \
  "-Dtest=RfqHandoffDraftQuoteControllerTest,RfqHandoffDraftQuoteServiceTest,RequestActorResolverLocalDemoTest,ChannelRfqHandoffControllerAuthorityBoundaryTest,AiWorkControllerAuthorityBoundaryTest" test
PASS: 37 tests, 0 failures, 0 errors, 0 skipped

node --test apps/web-dashboard/tests/rfq-handoffs.test.mjs      PASS: 30 tests, 0 failures
node --test apps/web-dashboard/tests/demo-dashboard.test.mjs    PASS: 9 tests, 0 failures (was 8; +1 regression)
npm --prefix apps/web-dashboard run lint       PASS
npm --prefix apps/web-dashboard run typecheck  PASS
npm --prefix apps/web-dashboard run build      PASS: compiled, typechecked, generated 52/52 static pages
git diff --check                               PASS (clean)
```

Live browser walkthrough (dashboard driven through a real headless browser attached to `:3000`;
each step is a genuine in-page button click routed through the same-origin BFF and Core):

```text
/demo "Send demo Telegram RFQ" clicked (twice)
  POST /api/demo/rfq-handoff -> 200; body { handoffId=...903, status=PENDING_REVIEW, message=... }
  DB after repeated clicks: channel_rfq_handoff PENDING_REVIEW = 1; inbound_channel_event = 1 (replay-safe)
/channels/rfq-handoffs
  Generate suggestion  -> "ADVISORY ONLY", RISK MEDIUM, CONFIDENCE 50%, every next action "human approval required"
  Start review         -> POST .../start-review 200
  Create draft quote   -> POST .../from-rfq-handoff 200; draft DQ-... Status NEEDS_REVIEW, Validation NEEDS_REVIEW,
                          Human review Required, Audit RECORDED, Outbox NOT_REQUESTED, External write safety NO_EXTERNAL_WRITE,
                          line PAD-OE-04465 qty 2 EA, blocking issues CUSTOMER_NOT_RESOLVED / PRICE_NOT_RESOLVED
  Empty decision note  -> Complete/Decline both disabled (required-note gate)
  Note entered + Complete safe demo:
    Decision state = COMPLETE_DEMO; Quote state = DEMO_COMPLETED; Safe terminal state = SAFE_DEMO_TERMINAL;
    Audit = RECORDED; External execution = DISABLED; Connector call = NOT_INVOKED; Outbox = NOT_REQUESTED

DB after terminal decision (tenant-scoped):
  draft_quote status=DEMO_COMPLETED requires_human_review=true; handoff=CONVERTED
  audit RFQ_HANDOFF_DEMO_DECISION_RECORDED count = 1
  idempotency_key RFQ_HANDOFF_DEMO_DECISION rows = 1 / SUCCEEDED
  connector_command=0; connector_sandbox_execution=0; change_request=0; outbox_event=0
```

No `actorId`, `tenantId`, idempotency key, raw AI payload, audit event id, connector credential, or raw
backend error was rendered anywhere in the walked pages (verified via the accessibility tree and the BFF
response body). The one redacted failure observed was a `503 { "message": "The demo RFQ could not be
created." }` when the dashboard process was started without the server-only `ORDERPILOT_DEMO_MODE` /
`ORDERPILOT_DEMO_TENANT_ID` env (section 3) — the BFF failed closed with a safe message and made no Core
call. Re-running with the section-3 env produced the 200 flow above.

One bounded UX defect was found and fixed during this walkthrough: the `/demo` "Last demo calls" list
keyed rows on `label-message`, so clicking the same demo button twice produced duplicate React keys (a
console error and potential row duplication/omission). Each recorded call now carries a monotonic id
(`components/demo-dashboard.tsx`), with a regression assertion in `tests/demo-dashboard.test.mjs`.

Screenshots: not captured. Static-frame screenshot capture timed out repeatedly on this Windows host
(the same UI-runtime limitation noted in section 16). Evidence was instead captured from the live
accessibility tree, the browser network log, the BFF response body, and tenant-scoped PostgreSQL queries
— all reproduced above.

## 15. Screenshot and log evidence

Capture screenshots after:

1. pending RFQ detail is selected;
2. advisory-only result is visible;
3. review-required draft and line are visible;
4. terminal result and all three external-write safety markers are visible.

Do not include browser devtools payloads, actor/tenant headers, credentials, raw AI data, or database
connection strings in screenshots.

Backend startup evidence may be captured from the Flyway lines and the final safe result only. Do
not attach full logs if they contain internal stack traces.

## 16. What remains not proven

- PR #241 PostgreSQL execution: **proven (2026-07-04, PR #242)** — see section 14A. The disposable
  database started, the two-test integration command passed, and the double-click browser walkthrough
  reached `SAFE_DEMO_TERMINAL` with exactly one `PENDING_REVIEW` row and zero external-write rows.
- Browser click-through: **proven live (2026-07-04, PR #242)** via a real headless browser attached to
  the running dashboard on `:3000`; every step was a genuine in-page click routed through the BFF and
  Core (section 14A).
- Static-frame screenshots: not captured. Image capture timed out repeatedly on this Windows host, so
  evidence was taken from the live accessibility tree, network log, BFF response body, and PostgreSQL
  queries instead (section 14A). A future run on a host with working screenshot capture can attach the
  four images listed in section 15.
- Full backend suite and full CI: not run (targeted suites only).
- Production SSO/signed gateway deployment: not part of this local/demo proof.
- Worker/connector runtime: not run; database assertions prove no external work was requested.
- Live production Telegram delivery is not part of this local/demo proof; production webhook
  verification was not changed.
