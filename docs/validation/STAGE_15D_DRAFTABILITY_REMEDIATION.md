# OP-CAP-15D — Operator-Actionable Draftability Remediation

Turns the advisory draftability hints from OP-CAP-15C into a guided remediation flow. A blocked or warning
validation-review line no longer only says "blocked" — it carries stable, machine-readable remediation
metadata and the review UI surfaces a compact link straight to the **existing** OP-CAP-14C operator action
that can unblock the line. Purely additive and read-only; no new lifecycle, no new approval domain, no
migration, no external write.

## Purpose

Help the operator understand *what to fix* and *where to fix it*, without leaving the validation review
screen and without weakening any safety boundary. The backend draft-create command remains the final
authority and still rejects blocked lines regardless of what the UI shows.

## Read-only vs command boundary

- `GET /api/v1/validations/{validationRunId}/review/draftability` (added in 15C, extended here) stays a
  **pure read** — `VALIDATION_READ`, no draft created, no `ExceptionCase` created, no audit, no business
  mutation. 15D only adds advisory metadata to the response; it creates no records.
- The actual fixes are performed by the **existing OP-CAP-14C command endpoints** (`REVIEW_ACTION`):
  corrections, issue resolutions, approval requests. 15D introduces **no new write path**.
- The 15A/15B draft-create endpoints are unchanged and remain authoritative — a blocked selected line is
  still rejected with `409 DRAFT_PREPARATION_BLOCKED` (tested), even if a client ignores the UI.

## How remediation maps to existing OP-CAP-14C actions

Each line's `remediations[]` entry is `{ reasonCode, remediationType, targetIssueId?, targetLineItemId?,
recommendedAction }`. Reason → remediation mapping (computed from the reasons 15C already derives, reusing
the open-issue rows the service already loads — no duplication of the canonical readiness gate):

| Reason code | remediationType | Existing 14C action | Target |
| --- | --- | --- | --- |
| `BLOCKING_ISSUE_UNRESOLVED` | `RESOLVE_ISSUE` | Resolve a validation issue | `targetIssueId` (first blocking open issue) + `targetLineItemId` |
| `QUANTITY_NOT_NORMALIZED` | `CORRECT_LINE` | Correct an extracted line item | `targetLineItemId` |
| `UOM_NOT_NORMALIZED` | `CORRECT_LINE` | Correct an extracted line item | `targetLineItemId` |
| `SKU_NOT_VALIDATED` / `PRODUCT_MATCH_MISSING` | `CORRECT_LINE` | Correct line item (map SKU → product) | `targetLineItemId` |
| `WARNING_ISSUE_PRESENT` | `VIEW_ISSUE` | Inspect / resolve the warning issue | `targetIssueId` (first warning open issue) |
| `LINE_READY` / already drafted | (none) | — | remediations empty |

`remediationType` tokens: `RESOLVE_ISSUE`, `CORRECT_LINE`, `CORRECT_FIELD`, `REQUEST_APPROVAL`,
`VIEW_ISSUE`, `NONE`.

Frontend: the "Line readiness" section in `validation-review-draft-controls.tsx` renders one compact link
per remediation (`Resolve validation issue` / `Correct line item` / `Correct extracted field` /
`Request approval` / `View issue`). Each link points at the existing **Operator review actions** panel
(`#operator-review-actions` anchor on `ValidationReviewActionsClient`). The operator completes the action
in those existing 14C controls (which already carry the issue/line/field dropdowns). On success that
component already calls `router.refresh()`, which re-renders the server page and re-fetches draftability —
the line's readiness/remediation updates with no faked local state. No new remediation modal is added.

## Why UI actions are advisory convenience, not security

- The remediation link is navigation to an existing control — it triggers no mutation by itself.
- Frontend disabling of blocked/already-drafted selection (15C) and the remediation links are **UX only**.
  The authoritative gate is the backend: 14C commands re-validate, and the 15A/15B create endpoint
  re-applies the canonical readiness gate and rejects blocked lines. The hint mapping never relaxes a gate.
- A line hinted draftable that has since changed still fails closed on create.

## Tenant / security notes

- The draftability read is tenant-scoped; a foreign-tenant run returns `404 validation_run_not_found`, so
  a foreign caller cannot infer any remediation target ids (tested).
- `targetIssueId` / `targetLineItemId` are ids the service already loaded for the tenant-checked run —
  no cross-tenant id is ever surfaced. No raw payload, prompt, secret, or stack trace is exposed.
- All UI text is escaped React rendering; `recommendedAction` is shown via a plain `title` attribute.
  No `dangerouslySetInnerHTML`.
- Permissions unchanged: draftability `VALIDATION_READ`; corrections/resolutions/approvals `REVIEW_ACTION`.

## Known limitations

- The remediation link navigates to the shared Operator review actions panel; it does not pre-select the
  specific issue/line in those controls (the 14C component holds target selection in its own internal
  state). The operator picks the target there. Deep pre-selection is deferred.
- One remediation per reason; a line with several reasons shows several links.
- `REQUEST_APPROVAL` / `CORRECT_FIELD` labels exist for completeness; the current reason→type mapping emits
  `RESOLVE_ISSUE`, `CORRECT_LINE`, and `VIEW_ISSUE`. Field-level and approval-driven mappings can be added
  as new reasons surface, without changing the contract.

## Next recommended slice

OP-CAP-15E — deep-target remediation: carry the `targetIssueId` / `targetLineItemId` through a URL hash or
shared client state so clicking a remediation pre-selects the exact issue/line in the existing OP-CAP-14C
controls (still no new write path, backend stays authoritative).
