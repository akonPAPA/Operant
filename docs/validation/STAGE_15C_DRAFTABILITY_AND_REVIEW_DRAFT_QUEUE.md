# OP-CAP-15C — Validation Review Draftability Hints and Review-Origin Draft Queue (Lite)

Makes the review-to-draft workflow operationally clearer by adding **advisory per-line draftability
hints** before submit and a **lite, read-only queue** of drafts created from validation reviews. Purely
additive and read-only — no new draft domain, no new lifecycle, no migration, no external write.

## Relation to OP-CAP-15A / 15B

- **15A** added the validation-run → internal Draft Quote/Order bridge (readiness-gated, idempotent,
  audited) over the canonical `ExceptionCase`/`DraftQuote`/`DraftOrder` domain.
- **15B** added existing-draft visibility (`draft-status`), selected-line subsets, and a bounded operator
  note, preserving one-draft-per-source-review.
- **15C** keeps all of that intact and adds two **read-only** surfaces: line draftability hints and a
  cross-run review-origin draft queue. The 15A/15B create endpoints are unchanged and remain authoritative.

## What draftability hints do

`GET /api/v1/validations/{validationRunId}/review/draftability` returns, per validated line:

- `lineItemId`, `lineNumber`, `draftable` (line-local), `severity` (OK | WARNING | BLOCKED)
- `reasons` (bounded codes), `normalizedSku`, `normalizedQuantity`, `normalizedUom`
- `hasBlockingIssue`, `hasWarningIssue`, `alreadyDrafted`, `sourceValidationRunId`, `sourceExceptionCaseId`

…plus a case-level snapshot: `draftExists` / `existingDraftType` / `existingDraftId` /
`existingWorkspacePath`, `caseDraftable`, `overallSeverity`, `caseBlockingReasons`, `lineCount`,
`draftableLineCount`, `externalExecution=DISABLED`.

Reason codes: `LINE_READY`, `BLOCKING_ISSUE_UNRESOLVED`, `WARNING_ISSUE_PRESENT`, `SKU_NOT_VALIDATED`,
`QUANTITY_NOT_NORMALIZED`, `UOM_NOT_NORMALIZED`, `PRODUCT_MATCH_MISSING`,
`LINE_ALREADY_INCLUDED_IN_EXISTING_DRAFT`, `CASE_NOT_DRAFTABLE`, `NO_DRAFTABLE_LINE_ITEMS`,
`UNKNOWN_BLOCKER`.

## Why hints are advisory and the backend stays authoritative

- The hint read **reuses the canonical readiness gate** (`DraftCommandPreparationService.readiness`) for
  `caseDraftable` — it does not re-implement or fork that logic. When no `ExceptionCase` exists yet it
  passes a **transient, unsaved** case to the same method, so there is **zero write side effect** and zero
  duplication of the gate.
- Line-level severity is a narrow mirror at line granularity (blocking issue / missing normalization /
  missing product match), not a second copy of the case gate.
- The POST create endpoints (15A/15B) re-validate on submit. If a line is hinted draftable but state has
  since changed, create **fails closed** (`409 DRAFT_PREPARATION_BLOCKED`). A blocked line sent by a
  client is still rejected (tested).

## Line status semantics

- **BLOCKED** — any hard line-local blocker (open CRITICAL/ERROR issue, quantity not normalized / ≤ 0,
  UOM not normalized, or product not matched). `draftable=false`, not selectable in the UI.
- **WARNING** — only non-blocking WARNING issues on the line. `draftable=true` (line-local); the
  case-level gate may still require review approval before any draft can be created.
- **OK** — ready; reason `LINE_READY`.
- **Already drafted** — the line is already included in the existing draft for this review
  (`alreadyDrafted=true`); shown distinctly and not re-selectable.
- `overallSeverity` is BLOCKED when the canonical case gate is not draftable, else WARNING if any line
  warns, else OK.

## Review-origin draft queue behavior

`GET /api/v1/validations/review-drafts` returns a tenant-scoped, paginated, `createdAt`-desc unified list
of internal drafts that have a `sourceValidationRunId` (validation-review origin). Each item:
`draftId`, `draftType` (QUOTE|ORDER), `sourceValidationRunId`, `sourceExceptionCaseId`, `customerDisplay`
(quotes only), `status`, `lineCount` (one grouped count query — no N+1), `createdAt`,
`operatorNotePresent` (**boolean only — never raw note content**), `workspacePath`, `reviewPath`,
`externalExecution=DISABLED`.

Filters: optional `draftType` (QUOTE|ORDER; invalid → `400 invalid_draft_type_filter`), optional `status`,
`limit` (default 25, clamped to max 100), `offset` (default 0). Drafts not created from a validation
review (no `sourceValidationRunId`) are excluded.

## Permissions

Both endpoints are GET under `/api/v1/validations` → **`VALIDATION_READ`** (same as validation-review
detail; auto-covered by the existing read-prefix). No `REVIEW_ACTION` required for these reads. No
interceptor code change was needed; permission tests assert the read-permission requirement.

## Tenant isolation

All lookups are tenant-scoped via `TenantContext`. A foreign-tenant run → `404 validation_run_not_found`
(draftability). The queue never returns another tenant's drafts. Selected line ids and draft ids cannot
cross tenants. Hints never expose another tenant's line ids.

## Audit behavior

Both endpoints are pure reads: **no audit events** are emitted (no noisy per-hint-fetch audit). Draft
creation audit from 15A/15B is unchanged.

## Idempotency behavior

No write idempotency is involved (read-only). The 15A/15B create idempotency
(`(tenant, sourceExceptionCaseId)`) and one-draft-per-source-review are unchanged.

## One-draft-per-source-review limitation (preserved)

A validation review still yields at most one internal draft. When a draft exists, draftability reports
`draftExists=true`, marks included lines `alreadyDrafted`, and the UI disables new creation (the existing
15B behavior). The queue shows each such draft once with links back to its source review.

## Frontend

- `lib/validation-review-draft-command-api.ts` — adds `getValidationReviewDraftability` (GET) + types.
- `lib/validation-review-draft-queue-api.ts` — read-only `getReviewDraftQueue` client (queue endpoint only).
- `components/validation-review-draft-controls.tsx` — compact "Line readiness" section (Ready / Warning /
  Blocked / Already drafted pills + short reason text); blocked/already-drafted lines are not selectable;
  default selection excludes non-draftable lines. Reasons render as **plain text** (no
  `dangerouslySetInnerHTML`). Backend remains the final authority (client disabling is not a security
  control — the server re-validates `selectedLineIds`).
- `app/(dashboard)/validations/[id]/review/page.tsx` — fetches draftability server-side and passes it to
  the narrow client controls (page stays server-rendered).
- `app/(dashboard)/workspace/review-drafts/page.tsx` — server-rendered queue list with links to the draft
  workspace and back to the source validation review; empty/error states; safety wording. Nav entry
  "Review-Origin Drafts" added.

## Explicit non-goals

- No ERP/1C/external connector write.
- No final order approval / lifecycle expansion.
- No payment/reconciliation layer.
- No command gateway / event foundation.
- No product/customer/inventory/price master-data mutation.
- No quote/order workspace redesign; no bulk actions.

## Next recommended slice

OP-CAP-15D — operator-actionable draftability: inline "resolve this blocker" affordances on the review
screen (deep-link each blocked line's reason to its existing 14C correction/issue-resolution action),
turning advisory hints into a guided remediation flow without expanding write scope.
