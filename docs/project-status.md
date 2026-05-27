# OrderPilot Project Status

Date: 2026-05-23

OrderPilot is a secure AI-assisted transaction intelligence platform for B2B auto/industrial parts distributors.

## Repository Survival

Status: INTACT

The repository survived the Codex cache/temp deletion. Required project paths are present:

- `apps/core-api`
- `apps/web-dashboard`
- `apps/ai-worker`
- `infra/docker/docker-compose.yml`
- `scripts/seed-demo-data/seed-core-v1.ps1`
- `packages/test-fixtures`
- `docs`
- `.github/workflows`

## Git Checkpoint

- Branch: `docs/stage-12-company-readiness`
- Latest commit: `72fecad feat: complete data foundation runtime baseline`
- Dirty worktree before these handoff docs were added: 56 modified tracked paths and 29 untracked paths.
- Dirty worktree after these handoff docs were added: 56 modified tracked paths and 32 untracked paths.
- High-risk dirty areas: core-api controllers/services/domain/migrations/tests, web-dashboard dashboard pages/components/libs, docs, fixtures, and demo seed script.
- Worktree assessment: consistent enough to build and test, not damaged. It is intentionally dirty and should not be reset or normalized without explicit user direction.

## Current Implementation Status

- Stage 1 Platform Foundation: DONE and currently verified by backend/frontend/worker checks.
- Stage 2 Data Foundation and Import/Demo Seed: DONE and currently verified by backend/frontend/worker checks. Seed script exists but needs a running core-api at `http://localhost:8080`.
- Stage 3 Omnichannel Intake Stabilization: DONE and currently verified by backend/frontend/worker checks.
- Stage 4 AI-assisted Understanding Pipeline Skeleton: DONE and currently verified by backend/worker checks. The live filesystem still contains later-looking files, tests, migrations, and docs beyond Stage 4. Treat those later surfaces as pre-existing/experimental until the user explicitly asks to reconcile or continue them.
- Stage 5 Deterministic Validation Boundary: DONE and currently verified by backend checks. It writes validation workflow records only and does not create quotes, orders, connector commands, change requests, or external writes.
- Stage 6 Operator Review Workspace Boundary: DONE and currently verified by backend/frontend checks. It writes review workflow records only and does not create quotes, orders, connector commands, change requests, or external writes.
- Stage 7 Safe Bot Runtime Boundary: DONE and currently verified by backend/frontend checks. It writes inbound bot/intake/review workflow records only and does not create quotes, orders, connector commands, change requests, outbound messages, or external writes.
- Stage 8 Read-only Commerce Analytics Boundary: DONE and currently verified by backend/frontend checks. It reads tenant-scoped workflow data only and does not mutate source workflow, business, connector, or master-data records.
- Stage 9 Security Hardening, Reliability, and Investor Demo Readiness: implemented. Targeted backend tests passed for the new permission controller test and tenant isolation suite before a documentation wording assertion was fixed; the final rerun is pending because Maven dependency access was blocked by the sandbox/app usage limit. It adds demo-stage explicit permission denial, security/reliability documentation, observability and backup/restore guidance, and investor demo readiness assets. It does not add production auth/RBAC, final quote/order automation, external writes, paid AI/OCR, or production outbound messaging.

## Architecture

- `core-api`: Java 21 + Spring Boot.
- `web-dashboard`: Next.js + TypeScript.
- `ai-worker`: Python advisory worker with deterministic mock Stage 4 extraction skeleton.
- DB: PostgreSQL.
- Cache: Redis.
- Local orchestration: Docker Compose.
- Raw files and webhook payloads go through an object storage abstraction.
- Business data mutations must go through core-api services/commands.

Security boundaries to preserve:

- Frontend must not access the DB directly.
- AI worker must not write business tables directly.
- Bot/chat/channel adapters must not write business tables directly.
- Tenant isolation is mandatory.
- Raw uploaded/channel data is untrusted.
- AI outputs are advisory only.
- Important mutations must emit audit events.
- External writes require explicit controlled command/approval paths.

## Demo Data

- Seed script: `scripts/seed-demo-data/seed-core-v1.ps1`
- Fixtures: `packages/test-fixtures/stage2-demo` and `packages/test-fixtures/stage3-intake`
- Intended behavior: idempotent demo tenant/data seeding through core-api endpoints.
- Current checkpoint: script failed only because core-api was not running locally on port 8080.

## Current Intake

Implemented and visible in the live tree:

- File upload intake.
- API upload intake.
- Email webhook stub.
- Telegram webhook local/dev intake.
- Inbound event ledger.
- Inbound documents.
- Channel messages.
- Webhook events.
- Processing job placeholder.
- Object storage abstraction.
- Frontend upload, inbox, documents, inbound-events, and message visibility.
- Advisory extraction/understanding runs and results for inbound documents/messages.
- Deterministic validation runs, issues, match/check results, substitute candidates, and approval requirements.
- Operator review cases, grouped issue detail, suggested corrective actions, substitute suggestions, internal notes, and auditable operator actions.
- Safe bot runtime for inbound Telegram messages, deterministic intent classification, conservative policy decisions, internal handoffs, RFQ-intent records, and review-case links.
- Read-only analytics endpoints for intake, extraction, validation, review, bot runtime, workflow health, top issue codes, channel breakdown, and automation-readiness indicators.
- Stage 9 hardening artifacts for tenant isolation, OWASP API/LLM checks, audit safety, explicit demo permission checks, observability, backup/restore, demo readiness, and investor narrative.

## Current Limitations

- No real AI/OCR should be treated as production-ready for this checkpoint; Stage 4 uses deterministic mock/stub extraction only.
- No quote/order automation should be started from Stage 4 or Stage 5 output.
- No ERP writes.
- No production WhatsApp behavior.
- Telegram tenant mapping remains local/dev unless future code review proves otherwise.
- Processing jobs exist and can reference understanding runs, but no production OCR/LLM worker pipeline exists.
- Live repo contains Stage 9+ and quote/order/connector-looking code. Do not expand or rely on it without a dedicated reconciliation task.
- Stage 9 permission checks are header-based demo hardening, not production authentication. Full production auth/RBAC, signed webhook enforcement, rate limiting, malware scanning/quarantine, WORM audit storage, and disaster recovery drills remain future work.

## Verification Results

- `cd C:\OrderPilot\OrderPilot-Core\apps\core-api; mvn test`: last full run before Stage 9 changes passed. Stage 9 targeted run passed `CommerceAnalyticsControllerTest` and `TenantIsolationBoundaryTest`; `Stage9SecurityDocumentationTest` initially failed on missing checklist wording, which was fixed. Final rerun is blocked because Maven cannot resolve Spring Boot parent POM without network/escalated access.
- `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run typecheck`: PASS after allowing TypeScript build info write access.
- `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run lint`: PASS.
- `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run build`: PASS.
- `cd C:\OrderPilot\OrderPilot-Core\apps\ai-worker; .\.venv\Scripts\python.exe -m pytest`: PASS, 12 tests passed.
- `cd C:\OrderPilot\OrderPilot-Core; docker compose -f infra\docker\docker-compose.yml config`: PASS after allowing Docker config access.
- `cd C:\OrderPilot\OrderPilot-Core; powershell -ExecutionPolicy Bypass -File .\scripts\seed-demo-data\seed-core-v1.ps1`: FAIL because core-api is not reachable at `http://localhost:8080`.

## Verification Checklist

Expected pass signals:

- Backend: Maven reports `BUILD SUCCESS`.
- Frontend typecheck/lint/build: npm scripts exit 0; Next build reports compiled successfully.
- AI worker: pytest reports all tests passed.
- Docker: Compose config renders services without schema errors.
- Seed: script completes only when core-api is running and reachable at `http://localhost:8080`.

## Next Recommended Task

Start the next roadmap stage only after reconciling the authoritative roadmap file. Treat current Stage 10+ quote/order/connector/change-request surfaces as experimental until a dedicated task approves them.
