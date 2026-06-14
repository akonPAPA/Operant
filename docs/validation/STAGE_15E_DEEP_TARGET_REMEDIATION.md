# OP-CAP-15E — Deep-Target Remediation Selection

Makes the OP-CAP-15D remediation links precise. Clicking a blocked/warning line's remediation no longer
just jumps to the shared operator-actions panel — it **pre-selects the exact issue or line** in the
existing OP-CAP-14C controls, so the operator lands on the right target ready to act. Frontend-only,
additive, no new write path.

## Purpose

Shorten the review → fix → draft loop. The operator clicks "Resolve validation issue" / "Correct line
item" on a specific draftability line and the existing 14C control opens with that issue/line already
selected, instead of requiring them to find it again in a dropdown.

## Chosen target-passing mechanism

URL **search params + hash**, the App Router idiomatic option (no new modal, no global store):

- `ValidationReviewDraftControls` builds each remediation link as
  `{pathname}?reviewActionType=<TYPE>&reviewActionIssueId=<id|>&reviewActionLineItemId=<id|>#operator-review-actions`
  using `next/link` (soft navigation) + `usePathname()`. The hash scrolls to the existing panel.
- `ValidationReviewActionsClient` reads those params with `useSearchParams()` and derives the target.

The target ids come straight from the OP-CAP-15D draftability DTO (`targetIssueId`, `targetLineItemId`) —
**no backend change** was required.

## How it reuses the 14C controls

No new control or form is introduced. The existing 14C children are pre-targeted via their normal props +
React's "reset uncontrolled state via `key`" pattern:

- **Issue resolution** — `IssueResolutionControls` initializes `issueId` from `targetIssueId`; the parent
  keys it on `issue-${targetIssueId}` so a new remediation click re-mounts it with the new selection.
- **Line correction** — when `reviewActionType === "CORRECT_LINE"` with a `targetLineItemId`,
  `CorrectionForm` initializes to the `LINE_ITEM` target; keyed on `corr-${type}-${lineItemId}`.
- **Approval request** — `ApprovalRequestControl` pre-selects the optional line when
  `reviewActionType === "REQUEST_APPROVAL"`; keyed similarly.

Keying (instead of `useEffect` + `setState`) avoids the set-state-in-effect anti-pattern and keeps manual
selection intact: with no target params the keys are stable, so an operator's manual dropdown choice
persists; clicking a remediation re-mounts only the targeted control with the new preselection.

Completing the action still uses the **existing** `onApplied={() => router.refresh()}` path, which reloads
review data and draftability — a fixed line can drop from BLOCKED to WARNING/READY.

## Why it is UX-only, not security

- The link triggers no mutation; it only navigates and preselects an input.
- Target ids are accepted **only when they already exist** in the backend-provided, tenant-scoped review
  `detail` (`detail.issues` / `detail.lineItems`). A malformed, stale, or foreign-looking id in the URL
  simply does not preselect — no crash, no bypass, falls back to manual selection.
- The real authority remains the backend: 14C commands re-validate under `REVIEW_ACTION`, and the 15A/15B
  draft-create endpoint re-applies the canonical readiness gate and rejects blocked lines.

## Backend authority and tenant-security notes

- No backend change in this slice; draftability stays read-only (`VALIDATION_READ`).
- Tenant isolation unchanged: ids are validated against the already tenant-scoped detail; the draftability
  read 404s for a foreign-tenant run (OP-CAP-15C/15D), so no cross-tenant id is ever surfaced to deep-link.
- No new approval domain, no new draft lifecycle, no external write, no AI/bot/frontend DB write.
- All UI text is escaped React rendering; `recommendedAction` is shown via a plain `title` attribute; no
  `dangerouslySetInnerHTML`.

## Limitations

- Preselection covers issue resolution and line correction (and optional approval line). Field-level
  correction (`CORRECT_FIELD`) is not currently emitted by the 15D mapping, so no field preselection path
  is exercised yet.
- The remediation link does not auto-submit or auto-fill the reason — the operator still reviews and
  confirms the action (intentional: the human stays in the loop).
- Re-mounting a control on a new remediation click discards any in-progress manual input in that control
  (expected when switching targets).

## Next recommended slice

OP-CAP-15F — post-remediation continuity: after a 14C action refreshes draftability, surface a subtle
"line now ready — return to draft" affordance that scrolls back to the draft controls and re-offers the
unblocked line for selection, closing the review → fix → draft loop visually.
