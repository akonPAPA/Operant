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

- Branch: op-36-quote-draft-assembly-workflow (from OP-CAP-35)
- HEAD: (OP-CAP-36 implementation)
- Dirty state: uncommitted OP-CAP-36 changes

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

### OP-CAP-32 Contract Stabilization & Bad-Layer Regression Fix — done

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

### Current next slice: OP-CAP-35 Quote Review Operator Action Runtime Integration — completed

- Goal: migrate Quote Review cockpit mutation actions from local ad-hoc mutation handling to the shared `useOperatorAction` / operator-action runtime created in OP-CAP-34.
- OP-CAP-34 created the reusable runtime foundation (`useOperatorAction`, `OperatorActionResult`, `createOperatorIdempotencyKey`, `mapOperatorActionError`).
- Quote Review previously had a local `mutate()` wrapper that was pattern-compliant but duplicated the loading/disabled state, duplicate-click guard, and error handling.
- Done this slice:
  - `components/quote-review-cockpit.tsx`: replaced the local `mutate()` wrapper and `mutationInFlight`/`mutationInFlightRef` state with the shared `useOperatorAction` hook. All 7 Quote Review mutation actions (resolve issue, escalate issue, fix qty, set EA, follow-up, select substitute, reject substitute) now use `execute()` with `mapOperatorActionError` for safe error mapping. Idempotency keeps the existing UUID-per-action pattern via `generateIdempotencyKey` (not weakening to the deterministic runtime helper). Message styling uses `messageKind` state ("done"/"error") instead of content-based substring matching.
  - `lib/quote-review-api.ts`: `requestQuoteReview` now attaches HTTP `status` to thrown errors so callers can use `mapOperatorActionError` for status-specific safe messages.
  - `lib/operator-action-runtime.ts`: no changes needed (the existing hook is sufficient).
  - Tests: `tests/quote-review-cockpit.test.mjs` — added 9 OP-CAP-35 tests (runtime import, hook usage, disabled state, duplicate-click guard, idempotency key approach, business-intent-only payloads, error mapping via `mapOperatorActionError`, API client status attachment, safe message rendering). Total 13 tests, 0 failures.
  - Verification: `npm run tsc` clean, `npm run lint` clean, `npm run build` success, all regression tests pass (quote-transaction-contract 8/8, channel-to-quote-ui 13/13, conversion-review 3/3).

### OP-CAP-36 Quote Draft Assembly Workflow v1 — implemented and test-proven

- Goal: move from "operator reviews validation issues" to "operator can assemble a
  safe draft quote candidate after review." Real product workflow step, backend-owned.
- Backend draft assembly endpoint/service did NOT exist before this slice; it was
  added as a narrow command over the existing `DraftQuote`/`QuoteReviewService`/
  `QuoteLifecycleService`. No new draft entity and no migration (the `DraftQuote`
  under review *is* the draft; `draft_quote.status` is an unconstrained column).
- New endpoint `POST /api/v1/quote-review/{quoteId}/assemble-draft` (REVIEW_ACTION,
  auto-covered by the existing `/api/v1/quote-review` non-GET interceptor rule).
  Request body is business intent only (`reasonCode`/`note`); `Idempotency-Key`
  header required; tenant from `X-Tenant-Id`, actor from `RequestActorResolver`.
  Body-supplied authority/state/total fields are ignored (malicious-override test).
- Service `QuoteReviewService.assembleDraft`: locked fetch, reject terminal
  (`requireCorrectableLifecycle`), readiness gate via
  `QuoteLifecycleService.requireReadyForApproval` (blocks on open blocking issues /
  pending / rejected / blocked substitutes → `409`). Draft status =
  `DRAFT_ASSEMBLED` when no open approval remains, else `PENDING_APPROVAL`
  (approvalRequired = any OPEN `quote_approval_request`). Re-assembly recalculates
  the same draft. Audit `QUOTE_DRAFT_ASSEMBLED` (ids + prev/new status + reason +
  validation summary). No outbox / ChangeRequest / ERP-1C write (future OP-CAP-37).
- Response `Stage12CDtos.QuoteDraftSummary`: operator-safe, backend-calculated
  (quoteId public handle, quoteNumber, draftStatus, customer summary, currency,
  subtotal/discount/total/margin, lineCount, unresolvedBlockingIssueCount,
  warningCount, stockWarningCount, approvalRequired, riskLevel, marginStatus,
  validationSummary, nextAction, operatorMessage, externalExecution=DISABLED,
  assembledAt). No tenantId/actorId/createdBy/approvedBy/sourceId/auditEventIds.
- Frontend: `lib/quote-review-api.ts` adds `QuoteDraftSummary` + `assembleQuoteDraft`
  (reuses `requestQuoteReview` — header tenant, status-attached errors). Cockpit
  `components/quote-review-cockpit.tsx` adds an "Assemble Draft Quote" action through
  the shared `useOperatorAction`/`doAction` runtime (idempotency-key lifecycle,
  duplicate-click guard, `mapOperatorActionError`); button gated by
  `hasOpenBlockingIssue`; a "Draft Quote Summary" panel renders safe fields only.
- Tests: backend `QuoteReviewServiceTest` (+5 → 16: clean assemble→DRAFT_ASSEMBLED,
  blocking-issue blocked, escalation→PENDING_APPROVAL/HIGH, terminal blocked,
  cross-tenant denied) and `QuoteReviewControllerTest` (+1 → 6: trusted-actor +
  malicious-override ignored + response leak checks). Frontend
  `tests/quote-review-cockpit.test.mjs` (+4 → 19). Verification: backend targeted
  6+16 (and Postgres integration 4) pass; FE `tsc` clean, `lint` clean, `build` OK,
  regression `quote-transaction-contract`/`channel-to-quote-ui`/`conversion-review`
  27/27 pass.
- Not proven / remaining risk: header totals are read from the existing
  backend-calculated `DraftQuote` (no new pricing engine; not recomputed at assembly
  time). Outbox/ChangeRequest emission on draft-assembled is deferred to OP-CAP-37.

### OP-CAP-36 closure + OP-CAP-37 Draft-Assembled ChangeRequest Candidate — implemented and test-proven

- OP-CAP-36 closure: `draft_quote.status` is an unconstrained `VARCHAR(40)` with no
  DB CHECK constraint, JPA enum converter, Java enum, or central code allowlist, so
  `DRAFT_ASSEMBLED` needs **no migration**. Downstream gates confirmed safe:
  conversion (`QuoteApprovalStateMachineService.convertApprovedQuoteToInternalDraftOrder`)
  still requires exactly `APPROVED`; connector ChangeRequest creation
  (`ChangeRequestService.requireEligibleQuote`) and handoff
  (`QuoteHandoffReadinessService`) still require `APPROVED`/`APPROVED_INTERNAL`; the
  order-journey projection treats `DRAFT_ASSEMBLED` as a non-terminal in-progress
  status; and the approval state machine accepts `DRAFT_ASSEMBLED` → `APPROVED`. The
  only real defect was a stale `docs/api/quote-review.md` line claiming command
  payloads include `tenantId`/`actorId`/`actorRole` (false since OP-CAP-31/35) — fixed.
  Added closure test `QuoteDraftServiceStage12ATest.draftAssembledQuoteCannotConvertButCanStillBeApproved`.
- OP-CAP-37: on a successful `assemble-draft` that yields `DRAFT_ASSEMBLED` (no
  approval pending), `QuoteReviewService.assembleDraft` now prepares exactly one
  tenant-scoped, non-executed external-sync ChangeRequest candidate via the existing
  `ChangeRequestService` (shape B — new narrow method
  `prepareQuoteExternalSyncCandidate`). Candidate: `targetSystem=INTERNAL_SYNC_CANDIDATE`
  (neutral target the demo executor refuses), `requestedAction=QUOTE_EXTERNAL_SYNC_CANDIDATE`,
  `sourceType=QUOTE_REVIEW`, `sourceId=quoteId`, `approvalStatus=PENDING_APPROVAL`,
  `executionStatus=EXECUTION_DISABLED`, with a server-built operator-safe snapshot.
  Dedup via a deterministic per-quote idempotency key (no duplicate active candidate
  under any request `Idempotency-Key`). Audit `QUOTE_DRAFT_EXTERNAL_SYNC_CANDIDATE_CREATED`/
  `..._REUSED`. **No** connector call, **no** outbox event (audit + candidate only),
  **no** external/ERP/1C write — externalExecution stays DISABLED. When approval is
  still required, no candidate is prepared this slice.
- Response: `Stage12CDtos.QuoteDraftSummary` gains `externalSyncCandidateStatus`
  (`PREPARED`/`PENDING_INTERNAL_APPROVAL`) — no candidate/connector/change-request id.
  Frontend cockpit renders it as safe business wording only ("External sync candidate
  prepared … no external execution"); no execution button, no connector UI.
- Tests: backend `QuoteReviewServiceTest` (+3 → 19: candidate prepared + tenant-scoped
  + non-executed + negative-proof no outbox/connector-sync; repeated-assemble dedup;
  pending-approval prepares none; cross-tenant denied creates no candidate),
  `QuoteDraftServiceStage12ATest` (+1 → 22 closure), `QuoteReviewControllerTest` (6,
  updated DTO + candidate-status/no-internal-id assertions), `ChangeRequestServiceTest`
  (10) and `ChangeRequestServiceStage9Test` (6) regression green — 63/63 targeted.
  Frontend `tests/quote-review-cockpit.test.mjs` (+1 → 20); `tsc` clean, `lint` clean.

### OP-CAP-47C Operator Fulfillment Timeline — render proof

- Stage: **OP-CAP-47C** — operator fulfillment timeline render proof and stage record. Frontend runtime
  verification; no product-scope expansion, no code changes to 47A/47B implementation.
- Branch / HEAD: `OP-CAP-47B-Frontend-Dev-Layer` @ `cc5d4fe` (OP-CAP-47B implementation uncommitted in
  working tree).
- OP-CAP-47B dependency: ✓ backend endpoint `GET /api/v1/order-journeys/{id}/operator-timeline`
  present; ✓ frontend surface present (`getOperatorFulfillmentTimeline` client + types
  `OperatorFulfillmentTimeline`/`OperatorTimelineEntry`; `operator-fulfillment-timeline.tsx`
  component; `<Suspense>` integrated into `order-journey-detail.tsx` → `/order-journey/[id]`).
- Files changed this slice: `apps/web-dashboard/tests/operator-fulfillment-timeline.render.test.mjs`
  (runtime render-proof harness; compiles real `.tsx` with installed `typescript` compiler, stubs
  network read with fixtures, renders via `react-dom/server`); `docs/ai/ORDERPILOT_ACTIVE_CHECKPOINT.md`
  (this record). No component, route, backend, migration, AI worker, or public tracking changes.
- Render states proven (6/6): empty (safe "No fulfillment signals recorded" message; summary fields
  render; no return badge); return requested (attention badge + note; surface read-only — no
  mutation/approval/execution control); multi-signal (entries out-of-order 3,1,2 render strictly by
  backend `sequence` order 1,2,3; status/source/evidence/customer-visible/internal labels render
  safely; receivedAt/processedAt as ISO timestamps); loading (Suspense skeleton, `aria-busy="true"`);
  error (safe mapped message only; no raw JSON, no stack trace). Decoy internal fields
  (journeyId/payloadRef/idempotencyKey/tenantId/sourceId/sourceRef) proven absent from all rendered HTML.
- Verification: `node --test tests/operator-fulfillment-timeline.render.test.mjs` 6/6 pass;
  combined `render (6) + source (12) + order-journey regression (11)` = 29/29 pass; `npm run typecheck`
  clean; `npm run lint` clean. No app-code/route changes this slice; build not re-run (OP-CAP-47B
  build already green).
- Data boundary: ✓ no frontend-owned authority; ✓ no mutation path (no `<form>`/`<input>`/`<button>`
  rendered); ✓ no raw backend/internal fields rendered; ✓ public tracking surface unchanged.
- Not proven: full app build this slice; real browser/E2E against live backend+DB; full backend suite;
  full CI; production readiness not claimed.
- Next action: commit OP-CAP-47A/B/C frontend slice together (only when explicitly asked), or run
  one-off live-backend smoke of `/order-journey/[id]` with a seeded journey.

### OP-CAP-53 Break-Glass & Incident Response Foundation — implemented and test-proven

- Builds on the OP-CAP-51/52 owner-company support foundation (do not redo). Adds a backend-only,
  controlled emergency incident-response layer. NO real data-repair execution, NO arbitrary SQL/script,
  NO direct DB mutation, NO connector/ERP write, NO real notification delivery.
- New domain pkg `com.orderpilot.domain.incident`: `IncidentRecord` (OPEN→CLOSED; CRITICAL cannot be
  silently closed without a closure reason), `BreakGlassAccessRequest` (REQUESTED→APPROVED|REJECTED|
  REVOKED|EXPIRED; scoped + reasoned + bounded-TTL + always-expiring; usable only while APPROVED and
  unexpired), `IncidentAlertRecord` (record-only, `PENDING_DISPATCH`, no external delivery), enums
  (`IncidentSeverity/Status/Type`, `BreakGlassScope/Status`, `AlertType/Status`) + 3 repositories.
- New service `IncidentResponseService`: createIncident/closeIncident/requestBreakGlass/approveBreakGlass/
  rejectBreakGlass/revokeBreakGlass/authorize. Self-approval denied (separation of duties); closed
  incident cannot receive a new approved grant; authorize fails closed on unknown/wrong-tenant/wrong-
  scope/wrong-actor/not-approved/expired/incident-not-open and emits safe denial audit; an observed
  expired grant transitions to EXPIRED + records a BREAK_GLASS_EXPIRED alert. Break-glass scopes are
  POLICY LABELS only — a valid grant mutates no business row. Every transition audited.
- New controller `InternalIncidentController` under `/api/v1/internal/support/**`: `POST /incidents`,
  `POST /incidents/{id}/close`, `GET /incidents/{id}`, `POST /tenants/{tid}/incidents/{iid}/break-glass-
  requests`, `POST /tenants/{tid}/break-glass-requests/{rid}/{approve|reject|revoke}`. Tenant from
  `X-Tenant-Id`, actor from `RequestActorResolver`; path tenant must equal context tenant (fail-closed).
- New permissions (route-edge, STAFF_* family, excluded from every tenant role by `ApiRolePermissionMatrix`):
  `STAFF_INCIDENT_CREATE/READ/CLOSE`, `STAFF_BREAK_GLASS_REQUEST/APPROVE/REVOKE`. `ApiRouteSecurityPolicy.
  supportDecision` extended (break-glass matched before incident since the create path is nested under an
  incident); unknown internal-support sub-routes still fail closed onto STAFF_*.
- Migration `V64__incident_break_glass_foundation.sql` — additive/non-destructive: CREATEs the 3 tables +
  indexes (tenant+status, incident_id, expires_at, requested_by). Touches no existing table.
- Request DTOs (`IncidentInternalDtos`) carry business intent only (no tenant/actor/status/approval/expiry/
  SQL/script/connector/secret); response DTOs expose no secret/actor field — proven by contract test.
- Tests added: `IncidentResponseServiceTest` (20 — incident + break-glass lifecycle, audit, no business
  mutation), `IncidentBreakGlassRoutePermissionTest` (12), `IncidentBreakGlassContractTest` (6),
  `InternalIncidentControllerSecurityTest` (8). Targeted security/regression sweep green: route inventory
  total=504 public=16 (unchanged) unclassified=0; combined incident+security 131/131 and broader
  support/coverage 262/262. `PostgresMigrationSmokeIntegrationTest` not run here (no local Postgres) —
  H2 proved entity↔schema mapping.

### Previous slice: TBD

- Goal: remove the remaining causes that let future agents reintroduce bad-layer bugs by making
  AGENTS.md instruction files durable (tracked, not local-only) and removing the last editable
  Tenant ID from the frontend channel-to-quote conversion panel.
- Done:
  - `.gitignore`: removed blanket `*AGENTS.md` ignore; AGENTS.md files are now tracked guardrails.
    `.claude/`, `*CLAUDE.md`, dumps, secrets, and graphify-out/ remain ignored.
  - `AGENTS.md` (root), `apps/core-api/AGENTS.md`, `apps/web-dashboard/AGENTS.md` — now version-controlled;
    content already carried the full OP-CAP-31 secure business contract law.
  - `components/channel-quote-conversion-panel.tsx`: editable Tenant ID input removed; tenant is
    resolved from `NEXT_PUBLIC_DEMO_TENANT_ID` env config as a module-level const (same pattern as
    `quote-source-context-panel.tsx`). A non-editable tenant display label remains for context.
    `tenantId` still flows through `X-Tenant-Id` header via the existing API client — body payload
    carries business intent only.
  - `docs/runbooks/ai-agent-workflow.md`: added "Agent Guardrails (AGENTS.md)" section documenting
    that AGENTS.md files are tracked guardrails, not local-only.
  - Tests: added no-editable-tenant-input assertion to `channel-to-quote-ui.test.mjs`.
  - No backend changes, no auth/session system, no new dependencies.

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