# OrderPilot Active Checkpoint

## Purpose

This file is the compact operational checkpoint for Claude Code. Read this instead of rediscovering the whole project or loading broad roadmap/status docs.

Do not treat this file as an archive. Keep it short.

## Current Repo State

- Project: OrderPilot Core
- Product type: production-leaning B2B SaaS transaction intelligence platform
- Backend: Java 21 / Spring Boot
- Frontend: Next.js / TypeScript
- AI worker: advisory only
- Core architecture: modular monolith, backend-owned business truth, tenant isolation, audit, idempotency, controlled external writes

## Current Branch / HEAD

Update at the start of a task only when needed:

- Branch:
- HEAD:
- Dirty state:

## Completed / Known State

- Stage 14 Root-Cause Merge / CI / CodeQL Stabilization is completed.
- OP-CAP-08B AI validation handoff operator review backend is complete:
  - Adds one tenant-scoped `ai_validation_handoff_review` row per 07F handoff, with review status,
    operator decision, bounded correction metadata, audit events, and `externalExecution=DISABLED`.
  - Endpoints: `GET /api/v1/ai-validation-handoffs/{handoffId}/review`; `POST .../review/start`;
    `POST .../review/decision`; `POST .../review/correction`.
  - Mutations require `REVIEW_ACTION`; `REVIEW_READ` alone is rejected.
  - `APPROVE_FOR_DRAFT_PREPARATION` is allowed only for already `draftEligible` handoffs and creates
    no draft, quote, order, connector command, ERP/1C write, or master-data mutation.
  - Targeted backend verification: 70 tests, 0 failures.
- OP-CAP-06F messenger bridge end-to-end closeout / runtime integration verification is complete:
  - Scope: integration-contract verification across OP-CAP-06A/B/C/D/D.1/E (no new capability).
  - Contracts verified consistent: permission (CHANNEL_IDENTITY_ACTION required for mutations; BOT_ACTION alone rejected; frontend client + workspace never send BOT_ACTION), identity resolution (no auto-link; linked→RESOLVED, suggested/needs-review→AMBIGUOUS, blocked→BLOCKED, unlinked/none→UNKNOWN; resolver and `ChannelIdentityResolutionMapper` aligned), contact/account (tenant-scoped contact+account validation, contact-only derives account, mismatch + cross-tenant rejected, locked mutation fetch retained), runtime gating (blocked never reaches runtime; ambiguous routes to operator review and is not treated as linked), operator workspace (real API, error surfacing, badge mapping, route present), audit/idempotency (idempotency guards + safe audit metadata preserved).
  - Concrete fixes: corrected stale `ChannelIdentityController` javadoc that still said mutations require BOT_ACTION (now CHANNEL_IDENTITY_ACTION); corrected "no PII"/"non-PII" wording in `CustomerContactResponse` (`Stage2Dtos`), `CustomerContactController`, frontend `CustomerContactSummary`, and this checkpoint to "minimal contact summary; direct contact details excluded".
  - Tests added: `ChannelBotRuntimeIdentityGatingTest.ambiguousIdentityPriceFlowRoutesToReviewAndIsNotTreatedAsLinked` (end-to-end proof a NEEDS_REVIEW/AMBIGUOUS sender's price flow routes to operator review via `CONFIG_BLOCKED:AMBIGUOUS_CUSTOMER_HANDOFF`, creates no conversation, and is audited).
  - Commands run: targeted backend `mvn -Dtest=...` 60 tests, 0 failures, BUILD SUCCESS; frontend `node --test tests/channel-identities.test.mjs` 23 tests, 0 failures.
  - Commands not run: full backend suite; `npm run lint`/`npm run build` (only comment-string wording changed on frontend — no logic change).
  - Remaining limitations: controlled runtime is Telegram-only this slice; no CustomerContactController integration test added (no existing close pattern to follow without broadening scope).
- OP-CAP-06A/06B messenger bridge/runtime foundation exists.
- OP-CAP-06E channel identity operator UX + frontend/API consumption is complete:
  - Route: `/channel-identities` (Next.js App Router, server pre-load + client workspace)
  - `lib/channel-identity-api.ts` — typed API client, all mutations use CHANNEL_IDENTITY_ACTION (not BOT_ACTION)
  - `components/channel-identity-workspace.tsx` — interactive operator workspace with list, detail, link dialog, unlink/block/needs-review with confirmation flows, loading/error states, client-side status filter
  - `components/navigation.ts` — added "Channel Identities" entry adjacent to Messenger Bridge
  - Backend: `CustomerContactResponse` DTO added to `Stage2Dtos`; `CustomerContactController` added at `GET /api/v1/customers/{customerId}/contacts` (read-only, tenant-scoped, minimal contact summary; direct contact details (email/phone) excluded)
  - Frontend tests: 23 tests, 0 failures (`node --test tests/channel-identities.test.mjs`)
  - Build: `npm run build` — compiled successfully, `/channel-identities` route generated
  - No auto-linking from inbound messages; no AI/bot direct mutation path
- OP-CAP-06D.1 channel identity security hardening gate is complete:
  - full contact+account tenant validation (Option A): contact-only link auto-derives accountId from validated contact; account/contact mismatch rejected; cross-tenant contact rejected
  - `CustomerContactRepository.findByIdAndTenantIdAndDeletedAtIsNull` added
  - `ChannelIdentityRepository.findWithLockByIdAndTenantId` (@Lock PESSIMISTIC_WRITE) added; all mutation commands use locked fetch
  - `ApiPermission.CHANNEL_IDENTITY_ACTION` added; interceptor updated (no longer BOT_ACTION)
  - `ApiPermissionInterceptorPermissionTest` — 7 unit tests, confirms BOT_ACTION is rejected for identity mutations
  - targeted self-check: 50 tests, 0 failures
- OP-CAP-06D channel identity operator control + read contract is complete:
  - `ChannelIdentityResolutionMapper` — static mapper, domain status → 5-status frontend read contract
  - `ChannelIdentityResolutionView` — stable sub-DTO added to `ChannelIdentityResponse`
  - `ChannelIdentityService` — added `markNeedsReview()`, idempotency guards on all commands, cross-tenant customer account validation, richer safe audit metadata
  - `ChannelIdentityController` — added `POST /{id}/needs-review`, wired resolution view into all responses
  - `ApiPermissionInterceptor` — `/api/v1/channel-identities` now guarded: GET→`ADMIN_SETTINGS_READ`, mutations→`BOT_ACTION`
  - targeted self-check: 38 tests, 0 failures
- OP-CAP-06C identity resolution backend slice is reported complete:
  - resolver enum/record/service
  - policy Context extension
  - bridge wiring
  - identity gating tests
  - targeted backend slice green: 33 tests, 0 failures
- OP-CAP-06C introduced deterministic tenant-scoped identity resolution before bot runtime policy.

## Current Development Direction

Preferred agent split:

- Claude Code: backend truth, domain, policy, API contracts, migrations, targeted backend tests.
- Codex: frontend app architecture, UI shell, read-only surfaces, mocks/API adapters, frontend tests.
- Frontend may use mocks before backend contract, but must not invent backend truth.

## Critical Safety Invariants

- AI suggests; rules validate; human approves if risky; backend writes; audit records.
- Frontend, bot, AI worker and connector do not directly write trusted business data.
- Tenant isolation is mandatory.
- Business mutations go through command services.
- External writes remain disabled unless explicitly gated by the active stage.
- No secrets in repo, logs, prompts or reports.
- Do not touch `.env`, secrets, generated build output, or unrelated dirty files.

## Token / Usage Control

- Do not read `ORDERPILOT_CORE_V1_AI_DEV.md` unless explicitly requested.
- Do not read full roadmap/reconciliation docs unless the task is roadmap/stage reconciliation.
- Do not spawn subagents unless prompt says `SUBAGENTS_ALLOWED`.
- Do not use browser/computer/MCP/remote tools unless explicitly requested.
- Do not perform broad repo audits.
- Use targeted search and targeted reads.
- Run targeted tests only unless the task is cross-cutting.

## Default Final Report

- Assumptions
- Changed files
- Tests run
- Tests not run
- Risks / blockers
- Next recommended step
- Stop condition
