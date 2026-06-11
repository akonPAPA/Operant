# OP-CAP-15B — Validation Review Draft Visibility, Selected Lines, and Operator Note

Makes the OP-CAP-15A draft bridge operationally usable from the validation review screen by adding
draft **visibility**, **selected-line** subset creation, and a bounded **operator note**. Purely
additive over the canonical draft-preparation pipeline — no new draft model, no migration, no external
write.

## Relation to OP-CAP-15A

OP-CAP-15A added the validation-run → internal Draft Quote/Order bridge (readiness-gated, idempotent,
audited) keyed on the existing `ExceptionCase`/`DraftQuote`/`DraftOrder` domain. OP-CAP-15B keeps that
bridge and behavior intact and extends it with additive overloads and one read endpoint.

## What OP-CAP-15B added

### Endpoints (on the canonical `ValidationController`, `/api/v1/validations`)
| Method & path | Permission | Purpose |
| --- | --- | --- |
| `GET /api/v1/validations/{validationRunId}/review/draft-status` | `VALIDATION_READ` | Whether a draft already exists |
| `POST /api/v1/validations/{validationRunId}/review/draft-quote` | `REVIEW_ACTION` | Create draft quote (now accepts selection + note) |
| `POST /api/v1/validations/{validationRunId}/review/draft-order` | `REVIEW_ACTION` | Create draft order (now accepts selection + note) |

`draft-status` is a GET → `VALIDATION_READ` (read-only, no write, no audit).

### Request (create) — `ValidationReviewDraftRequest`
`{ actorUserId?, selectedLineIds?, operatorNote? }` (body optional). `selectedLineIds` uses the
canonical extracted line item id (`lineItemId` in the OP-CAP-14A review detail).

### Draft visibility — `ValidationReviewDraftStatus`
`{ exists, draftType(QUOTE|ORDER|null), draftId, workspacePath, sourceValidationRunId,
sourceExceptionCaseId, lineCount, createdAt, externalExecution=DISABLED }`. Resolved by the draft's
`sourceValidationRunId` (no `ExceptionCase` is created on this read).

## Draft visibility behavior

- Tenant-scoped: a foreign-tenant run returns `404 validation_run_not_found`.
- `exists=false` before any draft; `exists=true` with type/id/`workspacePath`
  (`/workspace/draft-quotes|draft-orders/{id}`) and `lineCount` after creation.
- Pure read — no audit event emitted.

## Selected-line behavior

- Omitted `selectedLineIds` (null) → all eligible validated lines (OP-CAP-15A behavior, unchanged).
- Explicit empty list → `400 selected_lines_empty`.
- Any id not belonging to the run (including cross-run/cross-tenant ids, never present in the run's
  tenant-scoped line set) → `400 selected_line_not_found` (never silently ignored).
- Duplicates are de-duplicated safely.
- The draft is still built from validated/**normalized** values only (tested: normalized `5`, not raw
  `2`); selection narrows included lines but **never** relaxes run-level readiness gating — an open
  blocking issue still returns `409 DRAFT_PREPARATION_BLOCKED`.
- `no_draftable_line_items` (`400`) / `no_valid_line_items` (`400`) guard empty results.

## Operator note behavior

- Optional, trimmed, max **1000** chars; over-limit → `400 operator_note_too_long`.
- Persisted on the existing `DraftQuote.notes` / `DraftOrder.notes` column via additive `appendNote`
  domain methods (no migration).
- Rendered as plain text in the UI (controlled `<textarea>`, no `dangerouslySetInnerHTML`).
- Audit (`QUOTE_DRAFT_CREATED` / `ORDER_DRAFT_CREATED`) records `notePresent` + `noteLength` and
  `selectedLineCount` only — **not** the raw note content (no secret leakage in logs).

## One-draft-per-source-review limitation (preserved)

Idempotency remains `(tenant, sourceExceptionCaseId)`. If a draft already exists, the create endpoint
returns the existing draft with `alreadyExisted=true` and does **not** create a second draft — including
when the other type or a different selection/note is requested (the original draft is authoritative; the
replay's selection/note are ignored). The frontend shows an "already created" badge + link and disables
the create buttons.

## Frontend

- `lib/validation-review-draft-command-api.ts` — adds `getValidationReviewDraftStatus` (GET) and extends
  `createDraft{Quote,Order}FromReview` with `{ selectedLineIds?, operatorNote? }`; bounded
  403/404/409/400 messages.
- `components/validation-review-draft-controls.tsx` (`"use client"`) — existing-draft badge/link +
  disabled create when a draft exists; optional "create from selected lines only" with per-line
  checkboxes and an empty-selection client guard; bounded operator-note textarea with a length counter;
  `router.refresh()` after success.
- The review page stays server-rendered; it fetches draft status server-side and passes it to the narrow
  client controls (the read-only detail + 14D action client are unchanged).

## Security / tenant isolation / audit / idempotency

- All commands `REVIEW_ACTION`; draft-status `VALIDATION_READ`. Tenant resolved server-side; cross-tenant
  run/line is impossible (404 / `selected_line_not_found`).
- Readiness gating is never bypassed; selection cannot include another run's/tenant's line.
- Drafts use deterministic validated values — AI output never creates business data directly.
- Audit on create (with bounded note/selection metadata, no content); idempotent per source review.

## Explicit non-goals

- No ERP/1C/external connector write.
- No final order approval / lifecycle expansion.
- No product/customer/inventory/price master-data mutation.
- No quote/order workspace redesign.
- No payment/reconciliation layer.

## Next recommended slice

OP-CAP-15C — operator draft review queue surfaced from validation reviews (list/badge of drafts created
from reviews across runs), or per-line eligibility hints so selection can pre-mark non-draftable lines
before submit.
