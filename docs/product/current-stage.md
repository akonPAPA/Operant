# Current Product Stage

Date: 2026-06-04

This file is the canonical current-stage pointer for OrderPilot Core v1.

- Canonical status source: `docs/product/STAGE_STATUS_RECONCILIATION.md`
- Current recommended stage status: `PARTIAL` overall Core v1; `PASS` for the RFQ / Channel -> Draft Quote Review backend/API layer gate; `PASS` for read-only operator UI surfacing; mutation/operator action layer intentionally not implemented.
- Active gate: OP-CAP-06A — Messenger Chatbot Integration Layer Authorization Gate (owner-directed 2026-06-04). See `STAGE_STATUS_RECONCILIATION.md` section 16 for allowed/forbidden scope and required verification. The prior RFQ / Channel -> Draft Quote Review Layer gate remains PASS and is now historical.
- Capability freeze is lifted **only** for OP-CAP-06A as the explicitly chosen next safe executable slice. All other new bot/analytics/integration/AI/product-expansion work remains frozen.
- OP-CAP-06A extends the already-verified Stage 7 / Stage 10 bot runtime and Stage 12 channel integration. It must not introduce a parallel architecture and must not be renamed "Stage 15".
- Product capability work is allowed only from the completed layer gate decision and must remain within the explicitly accepted next slice.
- Current implementation should be treated as broad Core v1 medium-layer implementation, mostly partial or demo/local controlled, not production-complete.
- The next allowed executable slice after this gate must come from the reconciled roadmap. This UI surface does not authorize mutation/operator actions, connector commands, ERP/1C writes, public RFQ API work, or AI-worker changes.

OrderPilot is a secure AI-assisted transaction intelligence platform for B2B auto/industrial parts distributors.

## Active Baseline

- Stage 1 Platform Foundation: DONE / verified.
- Stage 2 Data Foundation and Import/Demo Seed: DONE / verified.
- Stage 3 Omnichannel Intake Stabilization: DONE / verified.
- Stage 4 AI-assisted Understanding Pipeline Skeleton: DONE / verified as an advisory-only skeleton.
- Stage 5 Deterministic Validation Boundary: DONE / verified as deterministic validation workflow records only.
- Stage 6 Operator Review Workspace Boundary: DONE / verified as review workflow state only.
- Stage 7 Safe Bot Runtime Boundary: DONE / verified as inbound bot workflow state only.
- Stage 8 Read-only Commerce Analytics Boundary: DONE / verified as tenant-scoped analytics only.
- Stage 9 Security Hardening, Reliability, and Investor Demo Readiness: implemented; final backend rerun is pending because Maven dependency access was blocked after the last documentation wording fix.
- Stage 14 Root-Cause Merge / CI / CodeQL Stabilization: DONE (infrastructure stabilization only; branch `stage-14-master-controlled-core-v1`, merge commit `19c34c0`). This is infrastructure work, not a product chatbot/AI/ERP stage.

Stages 10-13 (Bot Runtime Lite, Commerce Intelligence, Integration Control, demo/freeze) exist as partially-implemented code/docs; see the stage/layer mapping in `STAGE_STATUS_RECONCILIATION.md`. The live filesystem also includes later-looking code/docs beyond Stage 8. Do not treat those as approved active scope without a dedicated reconciliation step.

## Implemented Architecture

- Core API: Java 21 + Spring Boot.
- Dashboard: Next.js + TypeScript.
- AI worker: Python placeholder with advisory-only behavior.
- Database: PostgreSQL.
- Cache: Redis.
- Local orchestration: Docker Compose.
- Raw files and channel payloads: object storage abstraction.
- Trusted business mutations: core-api services/commands only.

## Demo Tenant and Data

- Seed script: `scripts/seed-demo-data/seed-core-v1.ps1`
- Demo fixtures: `packages/test-fixtures/stage2-demo`
- Intake fixtures: `packages/test-fixtures/stage3-intake`
- Seed intent: idempotent demo setup through core-api.

## Current Intake Capabilities

- File upload.
- API upload.
- Email webhook stub.
- Telegram webhook local/dev intake.
- Inbound event ledger.
- Inbound document and channel message records.
- Webhook event records.
- Processing job placeholder.
- Advisory extraction/understanding runs and results for inbound documents/messages.
- Deterministic validation runs, issues, match/check results, substitute candidates, and approval requirements.
- Operator review cases that group extraction evidence, validation issues, suggested corrective actions, substitute candidates, approval requirements, internal notes, and audit timeline events.
- Safe bot runtime records for inbound Telegram messages, deterministic intent classification, conservative policy decisions, handoff requests, RFQ-intent records, and review-case links.
- Read-only commerce analytics for intake, extraction, validation, review, bot runtime, workflow health, and automation-readiness indicators.
- Stage 9 security and reliability readiness docs, explicit demo permission header checks, tenant isolation verification, OWASP API/LLM review notes, backup/restore guidance, observability checklist, and investor demo script.

## Product Limits

- No real AI/OCR production pipeline yet; Stage 4 uses deterministic mock/stub extraction only.
- No quote/order automation in the approved Stage 1-8 baseline.
- No ERP writes.
- No production WhatsApp behavior.
- Telegram tenant mapping is local/dev unless future code review proves otherwise.
- Processing jobs can reference understanding runs, but no heavy production OCR/LLM worker pipeline exists.
- Stage 9 does not make OrderPilot fully production-ready; production authentication/RBAC, signed webhook enforcement, rate limiting, malware scanning, WORM audit storage, and exercised disaster recovery remain future hardening.

## Stage 6 Boundary

Stage 6 introduces operator review workspace boundaries only:

- Group validation issues for operator review.
- Present suggested fixes and approval requirements.
- Let operators start review, approve for next stage, reject, request correction, escalate, add notes, and confirm candidate matches inside review state only.
- Preserve AI-suggests, rules-validate, human-approves-if-risky, backend-writes, audit-records.
- No quote/order writes.
- No ERP writes.
- No external customer messaging.

## Stage 7 Boundary

Stage 7 introduces a safe bot runtime boundary only:

- Receive inbound Telegram messages.
- Normalize and store intake channel messages.
- Classify intent with deterministic rules.
- Apply conservative bot policy decisions.
- Create internal handoff and RFQ-intent records.
- Link conversations to review cases.
- Do not send production customer messages.
- Do not create quotes/orders.
- Do not approve reviews, substitutes, or discounts.
- Do not execute connectors or ChangeRequests.

## Stage 8 Boundary

Stage 8 introduces read-only commerce analytics only:

- Tenant-scoped operational counts and status breakdowns.
- Intake, extraction, validation, review, bot, and processing-job metrics.
- Top validation issue codes and approval requirement reasons.
- Automation-readiness indicators for visibility only.
- No quote/order creation.
- No approvals.
- No customer messaging.
- No connector or ChangeRequest execution.
- No master-data mutation.

## Stage 9 Boundary

Stage 9 hardens approved Stage 1-8 surfaces only:

- Tenant isolation and safe structured error verification.
- Explicit demo API permission header checks.
- OWASP API/LLM safety review documentation.
- Audit safety and append-oriented audit notes.
- Reliability, backup/restore, and observability runbooks.
- Demo fixtures/readiness checklist and investor demo script.
- No quote/order automation.
- No ERP/1C/SAP/Dynamics/Oracle writes.
- No connector or ChangeRequest execution.
- No Local Windows Connector or desktop agent.
- No paid OCR/LLM integration.

## Next Stage

The RFQ / Channel -> Draft Quote Review backend/API layer gate is PASS. Read-only operator UI surfacing for the frozen conversion-attempt review contract is also PASS through `/conversion-review` and `/conversion-review/[attemptId]`. The next roadmap stage should still be started only from the authoritative roadmap and the latest reconciliation findings. Treat existing Stage 10+ quote/order/connector surfaces in the dirty worktree as experimental unless explicitly reconciled.

Correct next slice after the gate: choose from the reconciled roadmap. Mutation/operator actions, retries, quote creation actions, connector commands, ERP/1C writes, public RFQ API work, and AI-worker changes remain separate gated slices.

## Authorized Next Capability: OP-CAP-06A

As of 2026-06-04, the owner has explicitly authorized **OP-CAP-06A — Messenger Chatbot Integration Layer** as the next safe executable capability slice. Full scope, allowed/forbidden boundaries, and required verification are recorded in `STAGE_STATUS_RECONCILIATION.md` section 16.

Summary:

- OP-CAP-06A bridges the secure managed `channel.ChannelConnection` intake path into the existing controlled bot runtime (`BotRuntimeService`). It does not build a new connection/message model — most of the messenger layer already exists from Stages 7/10/12.
- Allowed: wire verified per-connection messenger webhooks into the controlled bot flows; link `InboundChannelEvent` to bot conversations; reuse existing channel/bot services; add tenant-isolation, duplicate-replay, no-secret, and external-execution-disabled tests; surface channel status and conversation timeline in the dashboard; minimal non-destructive migration only if genuinely required.
- Forbidden: parallel architecture/models; bot approval of quotes/orders/discounts; master-data mutation from bot/frontend/AI worker; real outbound messenger sends; ERP/1C/connector writes; no-code bot builder; raw token exposure; stage renaming; destructive migrations.
- `externalExecution=DISABLED` remains enforced. The "AI suggests, rules validate, human approves, backend writes, audit records" safety model is preserved.

## OP-CAP-46I Local Proof Status

Date: 2026-06-25

- Stage: OP-CAP-46I — PostgreSQL Tracking Link Migration + Query Proof
- Branch and short HEAD: `OP-CAP-46I-postgres-tracking-link-migration-query-proof` / `c043fff`
- Previous blocker: Docker daemon was unavailable during Docker Desktop update/startup.
- Current blocker: none for local PostgreSQL/Testcontainers proof; Docker/Testcontainers became available.
- Duplicate-method CI blocker status: resolved / precondition clean.
- PostgreSQL proof status: PROVEN locally on real PostgreSQL/Testcontainers.
- PostgresMigrationSmokeIntegrationTest result: green.
- OrderJourneyTrackingLinkPostgresIntegrationTest result: green; 5 tests, 0 failures, 0 errors, 0 skipped.
- Targeted non-Postgres regression result: green.
- PostgreSQL/Testcontainers proof commands:
  - `mvn "-Dtest=PostgresMigrationSmokeIntegrationTest" "-Dorderpilot.postgres.integration.enabled=true" test`
  - `mvn "-Dtest=OrderJourneyTrackingLinkPostgresIntegrationTest" "-Dorderpilot.postgres.integration.enabled=true" test`
- Targeted non-Postgres regression command:
  - `mvn "-Dtest=OrderJourneyTrackingLinkServiceTest,OrderJourneyTrackingLinkRevokeControllerTest,OrderJourneyPublicTrackingControllerTest,ApiRouteSecurityClassificationTest,ApiPermissionRouteCoverageTest,ApiPermissionInterceptorPermissionTest,ApiSecurityWebConfigPermissionCoverageTest,ApiRouteSecurityPolicyDefaultDenyTest,ApiSecurityWebConfigTest" test`
- Not proven:
  - full backend suite unless separately run
  - full CI unless GitHub rerun passes
  - frontend
  - worker/runtime
