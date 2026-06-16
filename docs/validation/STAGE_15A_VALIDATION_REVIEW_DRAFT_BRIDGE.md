# OP-CAP-15A — Validation Review → Draft Quote / Draft Order Workspace Bridge

First controlled bridge from a reviewed **validation run** (OP-CAP-14A/B/C/D surface) to an internal
**Draft Quote / Draft Order**. It reuses the existing canonical draft-preparation pipeline; it adds no
new draft model, no migration, and no external write.

## Endpoints

Added to the canonical `ValidationController` (`/api/v1/validations`), keyed on the validation run:

| Method & path | Purpose |
| --- | --- |
| `POST /api/v1/validations/{validationRunId}/review/draft-quote` | Create an internal Draft Quote from the review |
| `POST /api/v1/validations/{validationRunId}/review/draft-order` | Create an internal Draft Order from the review |

Optional body: `ReviewActionRequest { actorUserId, reason }` (both optional). Permission:
**`REVIEW_ACTION`** (non-GET under `/api/v1/validations/{id}/review`, enforced by
`ApiPermissionInterceptor`). `VALIDATION_RUN` alone is **not** sufficient.

Response: `ValidationReviewDraftResult { draftId, draftType, draftStatus, sourceReviewId,
createdLineCount, unresolvedBlockingIssueCount, unresolvedWarningIssueCount, approvalRequired, created,
alreadyExisted, externalExecution(=DISABLED), nextAction, nextRoute }`.

## Draft domain decision — reused existing

No new domain or migration. The bridge `ValidationReviewDraftCommandService` maps the validation-run
review to its (find-or-create) `ExceptionCase` and delegates to the existing
`DraftCommandPreparationService`, reusing:

- existing `DraftQuote` / `DraftQuoteLine` / `DraftOrder` / `DraftOrderLine` domain (already carries
  `sourceValidationRunId`, `sourceExceptionCaseId`, `sourceExtractionResultId`, customer snapshot,
  status, line fields);
- the existing **readiness gate** (open hard/critical/approval-backed issues fail closed);
- existing **idempotency** via the `(tenant_id, source_exception_case_id)` unique index (migration V39)
  + `findFirstByTenantIdAndSourceExceptionCaseId` pre-check;
- existing **audit** (`DRAFT_PREPARATION_SUCCEEDED` / `..._BLOCKED` / `..._ALREADY_PREPARED`) and
  `OperatorAction` records.

A small additive overload `DraftCommandPreparationService.prepareDraft(caseId, actor, requestedType)`
lets the operator choose QUOTE vs ORDER explicitly; `requestedType = null` preserves the existing
OP-CAP-09A intent-driven behavior unchanged.

## Command rules / behavior

- Tenant resolved from `TenantContext`; the run is loaded tenant-scoped — cross-tenant → `404`.
- Refuses creation when the run has no extracted line items (`400 no_valid_line_items`).
- Refuses creation when unresolved blocking validation issues exist — `DraftPreparationBlockedException`
  → `409 DRAFT_PREPARATION_BLOCKED` with bounded `blockingReasons` (existing handler). Warnings follow
  the existing readiness policy (resolve/approve to proceed).
- Drafts are built from **validated/normalized** extracted values (e.g. normalized quantity/UOM,
  confirmed product match), never raw AI output.
- Idempotent: a repeat call returns the existing draft (`alreadyExisted = true`); one draft per source
  review.
- Internal draft only: `externalExecution = DISABLED`. No final/approved order, no ERP/1C/accounting/
  warehouse/connector write, no `ChangeRequest` execution.

## Frontend

- `lib/validation-review-draft-command-api.ts` — POST-only helpers (`createDraftQuoteFromReview`,
  `createDraftOrderFromReview`); `X-Tenant-Id` from `NEXT_PUBLIC_DEMO_TENANT_ID`; bounded
  403/404/409/400 messages (409 surfaces blocking reasons).
- `components/validation-review-draft-controls.tsx` (`"use client"`) — "Create draft quote / order"
  buttons, disabled when `blockingIssueCount > 0` with a concise issue summary, loading/success/error
  states, and a link to the created draft's `nextRoute` (`/workspace/draft-quotes|draft-orders/{id}`).
  On success it calls `router.refresh()`.
- The review page (`/validations/[id]/review`) stays server-rendered; only this narrow client component
  is added (read-only detail + 14D action client remain unchanged).

## Security / tenant / audit / idempotency notes

- Tenant isolation enforced on every load; cross-tenant create is impossible (404).
- `REVIEW_ACTION` required; AI/chatbot/frontend never write business data directly — the draft is built
  from deterministic, operator-reviewed validation artifacts.
- Readiness gate prevents draft creation while blocking issues are open.
- Audit emitted on success/blocked/duplicate; no raw AI payload, document body, prompt, secret, token or
  stack trace is stored or returned.
- Idempotent per source review via the existing unique-index convention.

## Tests

Backend (`ValidationReviewDraftBridgeStage15ATest`, 8): draft quote + order from a clean run with source
traceability, validated-not-raw quantity, blocking issue fails closed, cross-tenant 404, idempotent
replay, audit emitted + no master-data mutation, no-line-items fails closed. Permission
(`ApiPermissionInterceptorPermissionTest`, +3): draft endpoints require `REVIEW_ACTION` (VALIDATION_RUN
rejected). OP-CAP-09A draft-prep + 14C command tests remain green.

Frontend (`tests/validation-review-draft.test.mjs`, 10): only the two 15A endpoints + tenant header,
no PATCH/PUT/DELETE, no forbidden endpoints, 403/404/409/400 mapping, buttons render + blocked state,
success link, no raw payload. Existing 14B/14D guard tests remain green (full FE suite 179/179, lint +
typecheck clean, build compiles).

## Known limitations

- Internal draft only — no final approval lifecycle expansion, no external ERP/1C write.
- One draft per source review (quote **or** order). Requesting the other type after one exists returns
  the existing draft (`alreadyExisted = true`) rather than creating a second.
- `selectedLineIds` / `operatorNote` / `customerOverride` are intentionally out of scope this slice
  (the existing `createFromValidation` builds the full validated line set); the request reuses the
  canonical `ReviewActionRequest`.
- No full quote/order workspace redesign; success links to the existing draft review routes.

## Next recommended slice

OP-CAP-15B — draft review queue entry point from the validation review (list/badge of drafts created
from reviews) and/or operator-selected line subset + operator note on draft creation.
