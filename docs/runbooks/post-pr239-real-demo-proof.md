
# Post-PR239 Real Demo Proof

## 1. Purpose

Prove the PR #239 RFQ demo flow against a real PostgreSQL database and the shipped dashboard:

```text
seeded Telegram RFQ handoff
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
```

These gateway settings are local/demo-only. Production and signed modes must not use unsigned
gateway headers.

Frontend PowerShell session:

```powershell
$env:NEXT_PUBLIC_CORE_API_URL = "http://localhost:8080"
$env:CORE_API_BASE_URL = "http://localhost:8080"
$env:NEXT_PUBLIC_ORDERPILOT_DEMO_MODE = "true"
$env:NEXT_PUBLIC_DEMO_TENANT_ID = "11111111-1111-4111-8111-111111111111"
$env:NEXT_PUBLIC_DEMO_PRODUCT_ID = "44444444-4444-4444-8444-444444444444"
$env:NEXT_PUBLIC_DEMO_LOCATION_ID = "33333333-3333-4333-8333-333333333333"
```

The frontend does not receive or send an actor ID. The backend resolves the local demo operator.

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

1. Open `http://localhost:3000/channels/rfq-handoffs`.
2. Open the pending Telegram request for `PAD-OE-04465`.
3. Click **Generate suggestion**.
4. Confirm the advisory summary and **Advisory only** marker.
5. Click **Start review**.
6. Click **Create draft quote**.
7. Inspect the quote number, quote/validation status, human-review marker, line, and safety summary.
8. Leave the decision note empty and confirm both terminal actions remain disabled.
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

- Automated visible browser click-through: not proven. The repository has no Playwright/Cypress
  setup, and both available UI-control runtimes were blocked on this Windows host by an ACL sandbox
  failure. The manual walkthrough remains required.
- Screenshots: not captured because automated browser control was unavailable.
- Full backend suite and full CI: not run.
- Production SSO/signed gateway deployment: not part of this local/demo proof.
- Worker/connector runtime: not run; database assertions prove no external work was requested.
- The legacy `/demo` **Send demo Telegram RFQ** button creates a legacy bot RFQ record, not the
  `channel_rfq_handoff` consumed by this workspace. See `docs/backlog/fix-notebook.md`.
