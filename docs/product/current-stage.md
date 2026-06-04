# Current Product Stage

Date: 2026-06-03

This file is the canonical current-stage pointer for OrderPilot Core v1.

- Canonical status source: `docs/product/STAGE_STATUS_RECONCILIATION.md`
- Current recommended stage status: `PARTIAL` overall Core v1; `PASS` for the RFQ / Channel -> Draft Quote Review backend/API layer gate; `PASS` for read-only operator UI surfacing; mutation/operator action layer intentionally not implemented.
- Active gate: RFQ / Channel -> Draft Quote Review Layer Completion Gate
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

The live filesystem also includes later-looking code/docs beyond Stage 8. Do not treat those as approved active scope without a dedicated reconciliation step.

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
