# Core V1 Local Demo Runbook

## Purpose

This runbook is for running and presenting OrderPilot Core v1 locally for a design partner, pilot review, or investor demo. It ties together the existing local scripts, Stage 7-10 safety boundaries, demo data fixtures, dashboard screens, and verification commands without enabling production connector behavior.

Use the existing detailed runbooks when you need deeper recovery steps:

- `docs/runbooks/LOCAL_DEMO_RUNBOOK.md`
- `docs/runbooks/demo-seed-data.md`
- `docs/runbooks/UAT_CHECKLIST_CORE_V1.md`
- `docs/investor/DEMO_SCRIPT_3_MIN.md`
- `docs/investor/DEMO_SCRIPT_10_MIN.md`

## Safety Boundaries

The local demo must preserve these boundaries:

- no production connectors;
- no real ERP/1C writes;
- no external connector network calls;
- no raw secrets in database, API responses, frontend output, audit metadata, logs, or docs;
- no inventory mutation through the Stage 9 integration path;
- no bot-triggered connector commands;
- `ChangeRequest` remains the external-write lifecycle object;
- Stage 9B connector execution remains demo-only;
- idempotency values are exposed as `connectorIdempotencyKeyHash` and stored as `sha256:*` hashes.

The Demo ERP adapter is local/in-process evidence only. It must not be presented as production ERP readiness.

## Local Prerequisites

Expected local tools:

- Java and Maven for `apps/core-api`;
- Node.js and `npm.cmd` for `apps/web-dashboard`;
- the repo Python virtual environment at `apps/ai-worker/.venv/Scripts/python.exe`;
- Docker Desktop for PostgreSQL and Redis through `infra/docker/docker-compose.yml`;
- PowerShell on Windows.

Path assumptions:

- repository root: `C:\OrderPilot\OrderPilot-Core`;
- backend: `C:\OrderPilot\OrderPilot-Core\apps\core-api`;
- frontend: `C:\OrderPilot\OrderPilot-Core\apps\web-dashboard`;
- AI worker: `C:\OrderPilot\OrderPilot-Core\apps\ai-worker`;
- Compose file: `C:\OrderPilot\OrderPilot-Core\infra\docker\docker-compose.yml`.

## Backend Commands

From `C:\OrderPilot\OrderPilot-Core\apps\core-api`:

```powershell
mvn test
mvn spring-boot:run
```

For the standard host-run backend, use local PostgreSQL at `localhost:55432` unless your environment overrides it:

```powershell
$env:ORDERPILOT_DB_NAME="orderpilot_local"
$env:ORDERPILOT_DB_USER="orderpilot_local_user"
$env:ORDERPILOT_DB_PASSWORD="change-me-local-dev-only"
$env:ORDERPILOT_DB_HOST_PORT="55432"
$env:SERVER_PORT="8080"
mvn spring-boot:run
```

Health checks:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/health"
Invoke-RestMethod "http://localhost:8080/actuator/health"
```

## Frontend Commands

From `C:\OrderPilot\OrderPilot-Core\apps\web-dashboard`:

```powershell
npm.cmd install
npm.cmd run lint
npm.cmd run test
npm.cmd run build
npm.cmd run dev
```

The standard local dashboard URL is:

```text
http://localhost:3000/demo
```

The dashboard expects `NEXT_PUBLIC_CORE_API_URL=http://localhost:8080` in local frontend configuration.

## AI Worker Commands

From `C:\OrderPilot\OrderPilot-Core\apps\ai-worker`:

```powershell
.\.venv\Scripts\python.exe -m pytest
```

If `python` or `py` is not on PATH, use the venv executable directly. That was the verified local path for the previous Core v1 pass.

## Docker And Local Services

Start PostgreSQL and Redis:

```powershell
cd C:\OrderPilot\OrderPilot-Core
docker compose -f infra\docker\docker-compose.yml up -d postgres redis
docker compose -f infra\docker\docker-compose.yml ps
```

Useful local ports:

- PostgreSQL for host tools: `localhost:55432`;
- Redis: `localhost:6379`;
- Core API: `http://localhost:8080`;
- Web dashboard: `http://localhost:3000`.

The Compose file also defines `core-api`, `web-dashboard`, and `ai-worker` services, but the standard developer demo can run backend/frontend from the host for easier debugging.

## Seed And Startup Helpers

Existing helper scripts:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\run-core-v1-demo-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\run-core-v1-acceptance.ps1
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\seed-local-demo.ps1
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\start-local-demo.ps1
powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-local-demo.ps1
```

`run-core-v1-demo-check.ps1` is a compact safe preflight wrapper. It validates local prerequisites, validates Docker Compose config when Docker is available, calls `check-local-demo.ps1`, prints the manual backend/frontend/worker commands, and points back to this runbook. It does not start production profiles or call external systems.

`run-core-v1-acceptance.ps1` is the Stage 11C acceptance evidence runner. It checks repo state, required demo scripts and runbooks, Core v1 scenario evidence, safety guardrails, and optional localhost runtime readiness, then writes `docs\runbooks\CORE_V1_ACCEPTANCE_EVIDENCE.md`. It does not start services, call external networks, enable production connectors, require real secrets, write ERP/1C, mutate real inventory, or allow bot/frontend/AI worker direct DB writes.

Preflight-only evidence report:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1
```

Strict runtime evidence report after backend, frontend, and local database are running:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1 -RequireRuntime
```

Use `-OutputPath` to write the report to a temporary or review-specific location:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1 -OutputPath "$env:TEMP\core-v1-acceptance.md"
```

Acceptance status meanings:

- `PASS`: evidence or localhost runtime behavior satisfies the Stage 11C runner criteria.
- `PARTIAL`: meaningful evidence exists, but the runner does not prove the full live scenario.
- `FAIL`: required evidence or strict runtime behavior is missing.
- `NOT_VERIFIED`: intentionally skipped or outside the current runner scope, usually because runtime mode was not requested.

`check-local-demo.ps1` defaults to preflight mode. Runtime-only checks, such as missing backend/frontend listeners, are warnings unless `-RequireRuntime` is supplied. Use `-RequireRuntime` before a live demo when backend and frontend should already be running.

`seed-local-demo.ps1` targets local deterministic demo data only. It uses fixed demo UUIDs and upserts / guarded inserts for repeatable local runs. It must not be used as a production import process.

## Recommended Local Demo Sequence

1. Open PowerShell at `C:\OrderPilot\OrderPilot-Core`.
2. Run a safe preflight:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-demo-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1
```

3. Start local services:

```powershell
docker compose -f infra\docker\docker-compose.yml up -d postgres redis
```

4. Seed deterministic local demo data and write frontend demo IDs:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seed-local-demo.ps1 -UpdateFrontendEnv
```

5. Start backend:

```powershell
cd apps\core-api
mvn spring-boot:run
```

6. Start frontend in a second shell:

```powershell
cd apps\web-dashboard
npm.cmd run dev
```

7. Run a runtime-strict demo check from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-local-demo.ps1 -RequireRuntime
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1 -RequireRuntime
```

8. Run worker verification if the AI worker changed or if you want a full local confidence check:

```powershell
cd apps\ai-worker
.\.venv\Scripts\python.exe -m pytest
```

9. Open `http://localhost:3000/demo`.
10. Follow `docs/investor/FOUNDER_DEMO_TALK_TRACK.md`.

## Demo Flow

1. Start local PostgreSQL and Redis.
2. Seed local demo data if needed.
3. Start core-api and confirm `http://localhost:8080/api/v1/health`.
4. Start web-dashboard and open `http://localhost:3000/demo`.
5. Open the dashboard navigation and inspect the inbox, documents, messages, command center, validation review, inventory/reconciliation, integrations, and audit surfaces that are present in the current build.
6. In Integrations, inspect the Demo ERP connection card and confirm `DEMO_ONLY` execution mode and placeholder credential status.
7. Inspect the ChangeRequest queue. If seeded approved validation-backed ChangeRequests exist, execute through the Demo ERP path only.
8. Execute the same approved ChangeRequest again only as an idempotency proof. Expected evidence: same external reference, same `connectorIdempotencyKeyHash`, `replay:true`, `networkCall:false`, no second external execution, and no Stage 9B `ConnectorCommand`.
9. Inspect connector audit timeline for attempt, success, failure, replay, cancel, and policy-block events where data exists.
10. Show non-demo policy-block evidence if seeded data exists. The expected audit event is `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`.
11. Close with the safety statement: no real ERP/1C write, no external connector network call, no raw secrets, no inventory mutation, and no bot-triggered connector command.

Do not claim a seeded example exists if it is not present in the local database. Use the docs and UI labels as the source of truth for what can be demonstrated in a given local run.

## Stage 11C Acceptance Evidence

Before sharing a local/investor demo result, generate the Stage 11C report:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1
```

For a live demo signoff, start and seed the local runtime first, then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-core-v1-acceptance.ps1 -RequireRuntime
```

The report must keep these disabled conditions explicit: production connectors, real ERP/1C writes, external connector network calls, raw secrets, real inventory mutation, bot-triggered connector commands, and direct database writes from bot/frontend/AI worker surfaces.

## Troubleshooting

### Maven Dependency Or Network Issue

Symptom: Maven fails resolving the Spring Boot parent with `Permission denied: getsockopt` or another repository access error.

Action: run Maven from the local developer environment with approved network access. Do not treat this as a product regression unless local approved Maven also fails.

### npm Install Or Build Issue

Symptom: `npm` is blocked by PowerShell policy or command lookup.

Action: use `npm.cmd` from `apps/web-dashboard`. If dependencies are missing, run `npm.cmd install` before lint/test/build/dev.

### Python Not On PATH

Symptom: `python` or `py` is unavailable.

Action: use `apps/ai-worker/.venv/Scripts/python.exe -m pytest`.

### Local Port Conflict

Symptoms:

- backend cannot bind `8080`;
- frontend cannot bind `3000`;
- PostgreSQL conflicts with a native service.

Actions:

- check the listener with PowerShell or `scripts/start-local-demo.ps1`;
- stop the conflicting process, or use a clearly documented alternate port;
- keep `NEXT_PUBLIC_CORE_API_URL` aligned with the backend URL.

### Stale Frontend Build

Symptom: UI text or routes do not match current source.

Action: stop the dev server, remove `.next` if necessary, then rerun `npm.cmd run dev`.

### Missing Demo Data

Symptom: queues are empty or demo IDs are missing.

Action: run `scripts\seed-local-demo.ps1`, confirm `.env.local` demo IDs, and then run `scripts\check-local-demo.ps1`.

### Preflight Passes But Runtime Warnings Remain

Symptom: `run-core-v1-demo-check.ps1` or `check-local-demo.ps1` prints warnings for backend, frontend, PostgreSQL, or seeded IDs.

Action: this is expected when services are not running yet. Start local services, seed data, start backend/frontend, then rerun `check-local-demo.ps1 -RequireRuntime`.

## Expected Verification Baseline

The latest recorded local baseline before Stage 11A:

- backend `mvn test`: 312 tests, 0 failures, 0 errors, 0 skipped;
- `mvn "-Dtest=Stage9SecurityDocumentationTest" test`: 2 tests, 0 failures, 0 errors;
- frontend `npm.cmd run lint`: passed;
- frontend `npm.cmd run test`: 39 tests passed;
- frontend `npm.cmd run build`: passed;
- AI worker `.venv\Scripts\python.exe -m pytest`: 12 tests passed.

Rerun the relevant commands locally before a design partner or investor demo. Recorded verification is not a substitute for a same-day local demo check.
