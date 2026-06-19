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

- OP-CAP-09D Draft Review Queue, Navigation & Product Picker is complete:
  - Backend (extends existing `WorkspaceController`/`DraftReviewService`; no new subsystem): `GET /api/v1/workspace/draft-quotes/review-queue`, `GET /api/v1/workspace/draft-orders/review-queue`, `GET /api/v1/workspace/products/search`. All `REVIEW_READ` (queue auto-covered by existing `/workspace/draft-*` prefix; added `/api/v1/workspace/products`→REVIEW_READ).
  - Bounded `DraftReviewSummary` (no full line arrays — `lineCount` via one grouped count query; no raw AI/document/message payload). Filters: status allowlist (fail-closed 400 on unknown), exact sourceReviewCaseId, quote-only customerRef name-contains, limit clamp (default 25/max 100), createdAt-desc, cursor deferred. Tenant-scoped via `@Query` + `Pageable`.
  - Product picker reuses existing tenant-scoped `ProductRepository` SKU/name search (deleted excluded) + ACTIVE filter, requires non-blank q (else `[]`), limit clamp (default 10/max 25). `ProductPickerItem` = productId/sku/name/normalizedSku/status only — no cost/margin/supplier. Read-only.
  - Entity additions (no migration): `DraftQuote.getUpdatedAt()`, `DraftOrder.getUpdatedAt()`. Repo additions: `searchReviewQueue` (quote/order), grouped `countByDraft{Quote,Order}Ids`.
  - Frontend: server list pages `/workspace/draft-quotes`, `/workspace/draft-orders` (GET filter form + Link rows to 09C detail); nav entries "Draft Quote Review"/"Draft Order Review" (distinct from existing `/quotes`,`/orders`); API helpers `getDraftQuoteReviewQueue`/`getDraftOrderReviewQueue`/`searchWorkspaceProducts`; product picker integrated into `draft-review-workspace.tsx` correction form (read-only search → sets productId).
  - Tests: backend `DraftReviewQueueStage9DTest` (11), `ApiPermissionInterceptorPermissionTest` (+5 → 33); 09A/09B/controller tests still green (8/14/3/2). Frontend `tests/draft-review-queue.test.mjs` (11); full FE suite 124/124; `npm run lint` clean; `npm run build` OK (4 `/workspace/draft-*` routes). No external write / final approval / master-data mutation.
- OP-CAP-09C Operator Draft Review UI is complete (frontend-only; no backend change needed — 09B DTOs sufficed):
  - Routes: `app/(dashboard)/workspace/draft-quotes/[id]/page.tsx`, `app/(dashboard)/workspace/draft-orders/[id]/page.tsx` (server components fetching the bounded 09B review DTO).
  - `lib/draft-review-api.ts` — tenant-scoped typed client: `getDraftQuoteReview`/`updateDraftQuoteLine`/`markDraftQuoteReady` + order equivalents; `X-Tenant-Id`; only the bounded 09B fields; strips undefined PATCH fields; 403→permission message.
  - `components/draft-review-workspace.tsx` — shared `"use client"` workspace param'd by `draftType`; bounded header/source/status, internal-only badge, line table, per-line correction form with client guards (qty>0, price≥0, UOM≤16, text≤512) deferring to backend, mark-ready (reflects `WAITING_APPROVAL`/locked), loading/empty/error/forbidden states, refresh-from-backend (no client row duplication).
  - No raw AI/document/message payload fields referenced; no final-approval/ERP/1C/invoice/connector/external-sync controls.
  - Navigation: no nav link added (no draft-list endpoint exists — pages are opened by draft id; documented, no fake list).
  - Tests: `tests/draft-review.test.mjs` (10, source-inspection style — endpoint routes, page safety, no-raw-payload, no-external-controls, client validation). Full frontend suite 113/113. `npm run lint` clean; `npm run build` succeeds (both routes registered).
- OP-CAP-09B Line-Level Operator Draft Review Workspace Foundation is complete:
  - Extends existing `WorkspaceController` (`/api/v1/workspace`) — no new controller, no parallel subsystem. New `DraftReviewService` (workspace pkg) reuses `DraftQuote`/`DraftOrder`/`DraftQuoteLine`/`DraftOrderLine` + `OperatorActionService` (which already pairs an audit event).
  - Endpoints: `GET .../draft-quotes|draft-orders/{id}/review` (bounded detail), `PATCH .../{id}/lines/{lineId}` (bounded line correction), `POST .../{id}/mark-ready`. Detail exposed as additive `/review` sub-resource to preserve the existing raw-entity header endpoints.
  - Bounded DTOs in `Stage6Dtos`: `DraftQuoteDetail`, `DraftOrderDetail`, `DraftQuoteLineView`, `DraftOrderLineView`, `DraftLineCorrectionRequest`. No raw AI JSON/document/message text; `externalExecution=DISABLED`.
  - Correction fields (conservative): quantity (>0), uom (≤16 chars), description (≤512), unitPrice (≥0), optional productId (tenant-scoped read-only existence check), correctionReason (≤512, audit-only). Fail-closed on invalid input, line-not-in-draft, cross-tenant, and terminal/locked status (`APPROVED_INTERNAL`/`APPROVED`/`REJECTED`/`CANCELLED`/`REMOVED`). In-place update (no duplicate lines), recomputes line + header totals, returns draft/line to `NEEDS_REVIEW`.
  - Lifecycle: `mark-ready` → single transition to existing `WAITING_APPROVAL` ("ready for internal approval"), idempotent, fails closed from terminal. No final approval / external sync / ERP / order confirmation.
  - Entity additions (no migration — columns already existed): `DraftQuoteLine.getDescription()` + `applyOperatorCorrection(...)`; `DraftOrderLine` bounded getters + `applyOperatorCorrection(...)`; `DraftOrderLineRepository.findByIdAndTenantId`.
  - Permissions: `/api/v1/workspace/draft-quotes|draft-orders` GET→REVIEW_READ, mutations→REVIEW_ACTION (added to `ApiPermissionInterceptor`; previously the whole `/api/v1/workspace` prefix was unguarded).
  - Audit actions: `DRAFT_QUOTE_LINE_CORRECTED`/`DRAFT_ORDER_LINE_CORRECTED`/`DRAFT_QUOTE_MARKED_READY`/`DRAFT_ORDER_MARKED_READY` — ids + changed field NAMES + reason + actor only.
  - No external/ERP/1C/connector write, no outbox, no inventory/master-data mutation (test asserts Product/Customer/Inventory/Price/Discount/Margin counts unchanged).
  - Frontend deferred to 09C (no existing `/api/v1/workspace/draft-*` UI; existing dashboard consumes `/api/v1/quotes/drafts` + `/api/v1/quote-review`). No fake UI added.
  - Tests: `DraftLineReviewStage9BTest` (14), `WorkspaceDraftReviewControllerTest` (3), `ApiPermissionInterceptorPermissionTest` (+7 → 28). Targeted 45/45; broader workspace/security/Stage6/9/11 sweep 86/86, BUILD SUCCESS. `QuoteReviewPostgresIntegrationTest` still needs Postgres (not run here).
- OP-CAP-09A Draft Quote/Order Preparation Foundation is complete:
  - Maps the "AI validation handoff" concept onto the existing validation-backed review case (`ExceptionCase`) + `DraftQuote`/`DraftOrder` workspace foundation; no parallel `ai-validation-handoffs` subsystem was invented (it did not exist — the real path is `ValidationReviewController` + `DraftCommandPreparationService`).
  - New endpoint `POST /api/v1/validation-review/{reviewCaseId}/prepare-draft` (REVIEW_ACTION) → bounded `DraftPreparationResult` (`draftType`, `draftId`, `sourceHandoffId`, `status`, `created`, `alreadyExisted`, `externalExecution=DISABLED`, `nextAction=OPEN_OPERATOR_WORKSPACE`).
  - Intent mapping (conservative, fail-closed): `RFQ`→QUOTE, `PURCHASE_ORDER`→ORDER, everything else → `400 BAD_REQUEST`. Not-draft-ready → `409 DRAFT_PREPARATION_BLOCKED` via existing readiness gate.
  - Idempotency: migration `V39__draft_preparation_idempotency.sql` adds partial unique indexes `(tenant_id, source_exception_case_id)` on `draft_quote`/`draft_order`; `DraftCommandPreparationService.prepareDraft` returns existing draft on repeat (no duplicate). `createFromValidation(runId, caseId)` overloads stamp the source case id.
  - Security gap fixed: `/api/v1/validation-review` was previously unguarded; now GET→REVIEW_READ, mutations→REVIEW_ACTION in `ApiPermissionInterceptor`.
  - No external/ERP/1C/connector write, no outbox, no master-data mutation; audit records ids only (no raw AI JSON/document/message text).
  - Tests: `DraftPreparationFoundationStage9ATest` (8), `ValidationReviewPrepareDraftControllerTest` (2), `ApiPermissionInterceptorPermissionTest` (+4, total 21), existing `ValidationReviewBridgeStage5BTest` (17) still green. Targeted run 48/48; broader H2 sweep 109 pass. `QuoteReviewPostgresIntegrationTest` (4) errors are pre-existing env failures (needs Postgres on localhost:55432; not run here).
- Stage 14 Root-Cause Merge / CI / CodeQL Stabilization is completed.
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

### OP-CAP-31 Secure Business Contract Layer — implemented and test-proven

- Root cause (historical): public contracts allowed/represented client-owned authority and internal
  response leaks (frontend/request DTOs accepting tenant/actor/role; response DTOs exposing internal
  IDs, source IDs, audit/outbox/payload internals).
- Required invariant: all client-originated inputs are untrusted — frontend, Postman, curl, CLI,
  bot, connector, AI worker alike. "Hidden in the UI" is not security.
- Required path: `public DTO -> trusted tenant/actor resolver -> clean command -> service
  validation -> safe response DTO`.
- Instruction spine: root `AGENTS.md` (section 21), `apps/core-api/AGENTS.md`,
  `apps/web-dashboard/AGENTS.md` (the area files were (re)created in OP-CAP-32). Read the area
  `AGENTS.md` before editing that area.
- Done and proven: removed body-owned actor from channel-to-quote conversion (actor now from
  `RequestActorResolver`); split public RFQ/approval request DTOs from internal commands; trimmed
  `QuoteSourceContextDto` and `ChangeRequest`/outbox default responses to operator-safe fields;
  removed editable Tenant ID and raw source/actor IDs from the quote source panel and removed
  tenant/actor fields from frontend JSON payloads. The Stage9 connector change-request/sync/
  policy/safety/audit response DTOs are operator-safe: `Stage9IntegrationControllerTest` proves the
  responses do not expose `sourceId`, `createdByUserId`/`approvedByUserId`, `connectorIdempotencyKey
  Hash`, `credentialStatus`/`maskedCredentialRef`, connector capabilities, raw failure messages, or
  audit metadata/entity ids. Connector approve/create actor comes from the trusted header, not the
  body (two malicious-override tests).

### Current next slice: OP-CAP-32 Contract Stabilization & Bad-Layer Regression Fix — done

- Goal: stop the "fix one visible bug, reintroduce the bad layer nearby" cycle by making docs,
  checkpoint, runbook, request/response DTOs, frontend types, and tests all agree with the safe code.
- Done this slice:
  - Code: removed the dead `actorId` authority field from `Stage9Dtos.Stage9ApprovalRequest`
    (reject/cancel only ever used `reason()`; the acting user is server-resolved). Body-supplied
    `actorId` is ignored (unknown property).
  - Docs: `docs/api/quote-transactions.md` no longer documents `actorId`/`actorType` in the request
    body or `conversionAttemptId`/`sourceId`/`auditEventIds` in the response; it states intent-only
    requests, header-borne tenant, server-resolved actor, and operator-safe responses.
  - Frontend: `lib/quote-transaction-api.ts` `ChannelToQuoteResponse` no longer declares
    `conversionAttemptId`/`sourceId`/`auditEventIds`; `channel-quote-conversion-panel.tsx` no longer
    renders the (now hidden) conversion attempt id.
  - Runbook: the missing area `AGENTS.md` files referenced by `docs/runbooks/ai-agent-workflow.md`
    and root `AGENTS.md` §21.1 were created (`apps/core-api/AGENTS.md`,
    `apps/web-dashboard/AGENTS.md`).
  - Tests: added Stage9 reject/cancel body-actor-ignored coverage and a frontend response-leak
    assertion (see Test files below).
- Remaining / not yet proven: the editable Tenant ID input in `channel-quote-conversion-panel.tsx`
  (demo conversion tool) is retained because it feeds the legitimate `X-Tenant-Id` header and there
  is no parent tenant context for this standalone panel; backend tenant validation is the boundary.
  A dedicated admin diagnostic endpoint/permission for connector internals remains a future option.

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