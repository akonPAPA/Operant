# Roadmap

Status note: This document is historical/superseded for current-stage tracking. The canonical current-stage source is `docs/product/current-stage.md`, which points to `docs/product/STAGE_STATUS_RECONCILIATION.md`. Do not use this file alone to determine the active implementation stage.

## Current Phase: Stage 10 Security, Reliability And Investor Demo Hardening

OrderPilot Core v1 now connects advisory AI extraction results to deterministic validation, operator review, and an operator-facing workspace:

- Tenant-scoped extraction runs for inbound documents and channel messages.
- Advisory extraction results, fields, line items, suggestions, source evidence, confidence signals, and processing status.
- SKU, alias, OEM, UOM, inventory, pricing, discount, margin, and substitution checks.
- Validation issues, approval requirements, and line-level routing recommendations.
- Operator review cases created from validation outcomes.
- Internal draft quote/order preparation gated by validation, review status, substitution safety, audit, and tenant isolation.
- Dashboard routes for the validation-review queue and detail workspace.
- Operator actions for approving/rejecting review cases and preparing internal draft quote/order commands.
- Operator correction and override commands for resolving review risks before draft preparation.
- Product candidate selection, substitute risk cues, pending approval visibility, correction history, audit timeline, and safe draft preview.
- Manager approval decision handling and a shared backend draft readiness gate for review detail, preview, draft quote preparation, and draft order preparation.
- Final Phase 6 acceptance sweep for validation review workspace, readiness consistency, approval decisions, safe draft preview, and internal draft preparation.
- Bot Runtime Lite foundation for tenant-scoped Telegram-style message capture, deterministic intent classification, RFQ request draft capture, policy decisions, human handoff, and operator visibility.
- Telegram webhook operationalization for the supported inbound Update subset, using configured secret-token verification or explicit local/demo fixture mode.
- Operator-assisted bot response drafting with deterministic templates, policy-gated readiness, audit logging, and no-op/stub outbound transport only.
- Conversation-to-review handoff that links bot conversations and RFQ requests into existing operator review/exception handling without bypassing Phase 6 readiness.
- Operator queue hardening that keeps bot-originated handoffs separate from validation-backed readiness cases until explicit conversion work is added, with bot-only draft preview and preparation blocked by backend readiness gates.
- Stage 8A commerce intelligence read models for command-center request volume, channel mix, automation rate, exception rate, validation-backed reviews, bot-only handoffs, blocked unsafe draft attempts, draft preparation count, review cycle time, and discount/margin risk visibility.
- Stage 8B reconciliation foundation for expected-vs-actual stock detection, stale inventory warnings, discrepancy cases, likely-cause labels, product movement timelines, and command-center/inventory analytics cards.
- Stage 8C business value analytics for estimated operator hours saved, estimated labor cost saved, leakage/risk indicators, substitute recovered revenue indicators, configurable ROI assumptions, and exportable pilot ROI report payloads.
- Stage 9A integration control foundation for tenant-scoped demo ERP connections, approved ChangeRequest workflow, deterministic demo external references, connector sync/audit records, and no production ERP/1C writes.
- Stage 9B connector safety hardening for demo-only execution policy, explicit capabilities, credential placeholders, idempotent replay handling, retry/cancel metadata, connector audit visibility, and production readiness runbooks.
- Stage 9B review hardening stores/displays connector idempotency as `sha256:*` hashes, audits attempt/success/failure/replay/policy-block paths, keeps `ChangeRequest` as the lifecycle source, and avoids `ConnectorCommand` creation from the demo execution path.
- Stage 10 closes targeted demo-only integration-control safety and focuses on full verification, security evidence, reliability runbooks, investor demo scripts, UAT checklist, and demo/pilot readiness.

## Guardrails

- AI is advisory only.
- Deterministic validation owns the final business decision.
- AI output must not directly write master business data.
- Human approval is required for risky actions and external writes.
- Prompt-injection content is treated as untrusted customer text.

## Phase 5 Scope Boundary

Phase 5A produced validation outcomes and suggested next actions. Phase 5B connects those outcomes to review and internal draft command preparation. Phase 6A adds the operator workspace UI on top of that bridge. Phase 6B lets operators correct or override review risks through backend commands before draft preparation. Phase 6C improves candidate selection, approval visibility, correction history, audit timeline, and safe draft preview. Phase 6D adds manager approval decisions and a single backend draft readiness evaluator. Phase 6E closes the review/draft/approval cockpit with a final consistency sweep. Phase 7A starts Bot Runtime Lite with controlled message capture and handoff only. Phase 7B accepts real Telegram-style inbound webhook payloads behind the same policy boundary. Phase 7C prepares bounded response drafts and local stub-send state for operator-assisted replies. Phase 7D links bot conversations to operator queue cases. Phase 7E makes bot-originated handoffs explicit non-validation-backed cases. Stage 8A adds read-only commerce intelligence over the existing workflow data. Stage 8B adds reconciliation detection and indexed read-model query hardening. Stage 8C adds ROI-ready business value metrics using existing records and tenant-scoped assumptions. Stage 9A adds a controlled demo ERP integration facade and ChangeRequest execution proof point. Stage 9B hardens idempotency, retry, credential-boundary, and runbook behavior while keeping execution demo/local-only. It does not create production ERP/1C writes, reserve inventory, create connector commands from bot-only handoffs, call Telegram outbound APIs, or mutate external systems. Quote and order approval remains behind typed backend command services, deterministic validation, tenant policy, audit, and approval gates.

## Next Recommended Phase

After Stage 10 acceptance, production connector work should remain blocked until a separate security/runbook phase approves credential custody, environment separation, idempotency, compensating actions, operator permissions, rate limiting, and observability. Real outbound Telegram transport remains blocked until its own security/runbook acceptance. Bot-to-validation-backed RFQ conversion is also a separate explicit phase where operators remain the authority for risky or uncertain customer actions.

Verification note: sandboxed Maven can fail on dependency resolution when network access is restricted. In this Stage 10 pass, sandboxed `mvn test` was blocked by Maven Central dependency resolution, then approved Maven `mvn test` passed with 312 tests, 0 failures, 0 errors. Frontend verification passed with `npm.cmd run lint`, `npm.cmd run test` (39 tests), and `npm.cmd run build`. AI worker verification passed with `.venv\Scripts\python.exe -m pytest` (12 tests). Production connectors remain disabled and real ERP/1C writes remain out of scope.
