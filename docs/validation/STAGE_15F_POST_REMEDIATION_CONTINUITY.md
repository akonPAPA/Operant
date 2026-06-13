# OP-CAP-15F — Post-Remediation Continuity

Visually closes the review → fix → draft loop. After an operator completes a deep-targeted OP-CAP-14C
remediation action (15E) and the page refreshes, the review surface guides them back to the draft
controls and — when the previously blocked/warning line is now draftable — offers a subtle inline
"continue draft" affordance. Frontend-only; no backend change, no new write path, no new workflow domain.

## Purpose

Before 15F the operator fixed an issue in the operator-actions panel and then had to scroll back and
re-discover the line state themselves. Now the loop is:

```
line blocked → click remediation (15D/15E) → fix issue (14C) → refresh →
line now ready → scrolled back to draft controls → "Line #N is now draftable. Continue draft."
```

## Chosen return-to-draft mechanism

URL **search params + hash**, mirroring the 15E convention:

- On a successful 14C action, `ValidationReviewActionsClient.applied()` — the shared `onApplied` handler —
  checks whether the action was deep-targeted at a validated line (`targetLineItemId` from 15E). If so it
  calls `router.replace("{pathname}?reviewReturnToDraft=1&reviewReturnLineItemId=<id>&reviewReturnReason=remediation-completed#validation-review-draft-controls")`
  and then runs the **existing** `router.refresh()` (unchanged in all cases — with no deep target the
  behavior is exactly the pre-15F refresh).
- The draft controls section now carries `id="validation-review-draft-controls"` (alongside the existing
  `#operator-review-actions` from 15D/15E).

**Search params carry metadata; the hash only scrolls.** Params survive the refresh and are readable via
`useSearchParams` in the narrow client component; the hash is pure browser scroll behavior with no data
role. No `window.location` reads during render, no state synced from props/params in effects (the
affordance is computed during render), so there is no `react-hooks/set-state-in-effect` violation and no
`useSearchParams` Suspense bailout (the route is already dynamic).

## How it reuses 14C success + existing draft controls

- No new mutation, modal, wizard, or notification system. The continuity marker is set inside the existing
  success path of the existing 14C controls; the existing `router.refresh()` re-fetches review detail and
  draftability server-side.
- `ValidationReviewDraftControls` reads `reviewReturnToDraft` / `reviewReturnLineItemId`, resolves the line
  against its already-loaded draftability map, and renders the affordance only when the line exists and is
  now draftable (not `BLOCKED`, not `alreadyDrafted`).
- The optional "Select this line" button appears only in selected-lines mode when the line is not yet
  selected, and goes through the **existing** `toggleLine` logic (which already refuses non-selectable
  lines). With selection mode off, all eligible lines are included by default, so no action is needed.
- Default selection, manual selection, 15E deep-target preselection, and the 15C/15D blocked /
  already-drafted disabling behavior are unchanged.

## Why this is UX-only, not security

- The continuity marker triggers no mutation; it only navigates/scrolls and conditionally renders a hint.
- `reviewReturnLineItemId` is honored **only** when it matches a line in the backend-provided,
  tenant-scoped draftability data **and** that line is currently draftable. Malformed, stale, or
  foreign-looking ids resolve to nothing — no crash, no affordance, no bypass.
- Selecting the line still flows through the existing client guards, and the 15A/15B draft-create endpoint
  re-applies the canonical readiness gate server-side: a line that is *claimed* ready but is not will
  still fail closed (`409 DRAFT_PREPARATION_BLOCKED`).

## Backend authority and tenant-safety notes

- Backend untouched in this slice. Draftability remains a pure `VALIDATION_READ` read; 14C commands remain
  `REVIEW_ACTION`; create endpoints remain idempotent, readiness-gated, audited.
- Tenant isolation unchanged: the affordance can only ever reference line ids the backend already returned
  for the tenant-checked run; a foreign-tenant run 404s before any draftability data exists to deep-link.
- All UI text is escaped React rendering; no `dangerouslySetInnerHTML`.

## Limitations

- The continuity marker is only set for **deep-targeted** actions (a remediation link carried a validated
  `targetLineItemId`). Manually performed 14C actions refresh as before, with no return marker.
- If the fix did not actually unblock the line (e.g. another blocker remains), no affordance is shown —
  the line readiness list still explains the remaining reasons; there is no "still blocked" toast by design
  (no noisy notification system).
- The marker stays in the URL until the next navigation; it is idempotent and harmless on reload (the
  affordance simply re-renders while the line stays draftable).
- "Select this line" matters only in selected-lines mode; client selection state predating the fix may
  exclude the line until the operator selects it (one click) — intentional, no force-selection.

## Next recommended slice

OP-CAP-15G — remediation outcome summary on the review-origin draft queue: surface per-draft counts of
remediated-then-drafted lines (from existing audit/action data, read-only) so managers can see how often
the review → fix → draft loop is exercised, without adding any new write path.
