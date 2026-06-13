# OP-CAP-15I — Remediation Timeline and Rollup Read Model Polish

A **read-only** UX/data polish layer over the existing OP-CAP-15G summary and OP-CAP-15H lineage detail.
It adds two things, both derived from the *same* structured operator-action records already in place — no new
write path, no new permission, no AI-generated truth, no raw notes, no new endpoint:

1. **Per-line remediation timeline** on the 15H detail (`GET .../remediation-lineage`) — a deterministic,
   ordered projection of the corrections / issue resolutions / approvals already attached to each draft line.
2. **Compact remediation rollup** on each 15C/15G queue row (`GET /api/v1/validations/review-drafts`) — so an
   operator sees tenant-scoped remediation state without opening every detail page.

## Purpose

15G told operators *how much* remediation happened per draft (counts). 15H told them *exactly what*, per
line. 15I makes both easier to read at a glance: a chronological per-line timeline in the detail, and a
one-line rollup (action count, remediated/traceable lines, latest action time, limitation codes) right on the
queue. It deliberately stays a thin read model — not a generic analytics system, not an audit redesign.

## Endpoint / DTO changes

No route added or changed. Existing GET routes stay under `VALIDATION_READ`. Two existing DTOs gain fields
(additive, backward-compatible — no field renamed or removed):

```java
// 15H detail line gains a normalized, deterministic timeline (may be empty, never null):
record ValidationReviewDraftRemediationLineageLine(
    ...existing 15H fields...,
    List<LineageTimelineEntry> timeline) {}

record LineageTimelineEntry(
    String category,                       // CORRECTION | ISSUE_RESOLUTION | APPROVAL
    String actionType,                     // canonical 14C OperatorAction token
    UUID actionId,
    UUID targetLineItemId, UUID targetIssueId, UUID targetApprovalRequirementId,
    String status,                         // related issue/approval domain status
    String summary,                        // deterministic backend message (never a raw note)
    Instant createdAt) {}

// 15C/15G queue row gains a compact rollup alongside the unchanged 15G remediationSummary:
record ValidationReviewDraftQueueItem(
    ...existing 15C/15G fields...,
    ValidationReviewDraftRemediationRollup remediationRollup) {}

record ValidationReviewDraftRemediationRollup(
    boolean remediationLineageAvailable,
    int remediationActionCount,            // correction + issue-resolution + approval
    int remediatedLineCount,
    int traceableLineCount,                // drafted lines carrying a source line id
    List<String> limitationCodes,
    Instant latestRemediationActionAt) {}  // max createdAt across attached actions, or null
```

## Detail timeline behavior

`ValidationReviewDraftRemediationLineageService` builds the timeline per drafted line by flattening that
line's already-attached correction / issue-resolution / approval actions into `LineageTimelineEntry` records,
sorted by `createdAt` ascending then `actionId` (fully deterministic). Rules:

- Only **line-scoped structured actions** appear — field-level corrections target a field id and are never
  attached, so they never enter the timeline.
- `summary` is the deterministic backend-authored `OperatorAction.message`, never the operator's free-text
  reason/note and never AI content.
- A line with no attached actions returns an **empty list, not null**.
- The non-review-origin path is unchanged (`available=false`, empty `lines`).

## Queue rollup behavior

`ValidationReviewDraftQueryService` now derives both the 15G summary **and** the 15I rollup in one pass over
the **same fixed set of bounded batch queries** (no extra per-row query, no N+1). The rollup adds only two
genuinely new derivations from data already loaded:

- `traceableLineCount` — counted from the existing `(draftId, sourceLineId)` batch projection.
- `latestRemediationActionAt` — max `createdAt` across the structured actions attached to the draft
  (tracked alongside the existing per-target counts).

`remediationActionCount`/`remediatedLineCount` mirror the 15G/15H counts exactly (a regression test asserts
`rollup.remediationActionCount == detail.correctionActionCount + issueResolutionActionCount +
approvalActionCount` and equal remediated/traceable line counts). When lineage is not derivable (a draft
whose lines carry no source line ids), the rollup is `remediationLineageAvailable=false` with zero counts,
`latestRemediationActionAt=null`, and `limitationCodes=[structured_action_lineage_missing]`.

## Frontend

- `lib/validation-review-draft-queue-api.ts` — added `ValidationReviewDraftRemediationRollup` +
  `remediationRollup` on the queue item, and `LineageTimelineEntry` + `timeline` on the lineage line.
- `app/(dashboard)/workspace/review-drafts/page.tsx` — compact rollup cell per row: action count,
  `remediated/traceable` lines, latest action timestamp, and (when unavailable) limitation codes as `<code>`
  tokens. The existing remediation summary cell and "Remediation lineage" link are unchanged.
- `app/(dashboard)/workspace/review-drafts/[draftKind]/[draftId]/remediation-lineage/page.tsx` — a
  "Remediation timeline" table (line · when · category · action · status · summary), with an explicit
  empty state when no actions were recorded. Server-rendered, no client effects, no unsafe HTML.

## Security invariants

- **Read-only** end to end: no new command, no audit write, no external/ERP/1C/connector execution
  (`externalExecution` stays `DISABLED`).
- **Tenant isolation**: all derivation runs through the existing tenant-scoped queries; a cross-tenant action
  never enters a rollup or timeline (regression test asserts a foreign-tenant correction yields zero counts
  and a null latest timestamp).
- **No raw content**: only ids, enum/token strings, deterministic backend messages, counts and timestamps —
  never raw operator notes or AI payload. No actor id is exposed (the domain does not expose one safely).
- **Permission**: unchanged `VALIDATION_READ` for GET; no new route, so no new permission test surface.
- **Deterministic**: timeline ordering and all counts are pure id/timestamp derivations — no inference.

## Limitations

- `latestRemediationActionAt` is the max `createdAt` across attached structured actions; like the 15G/15H
  counts it is id/timestamp-linkage, not a causal "fixed before draft" proof.
- Field-level corrections remain excluded from line lineage and the timeline (not line-scoped), as in 15G/15H.
- The rollup reuses the queue's existing bounded page (limit clamped to 100); it is not a tenant-wide
  aggregate and deliberately does not become one.

## Test coverage

Backend — `ValidationReviewRemediationTimelineRollupStage15ITest` (9):
- timeline includes correction + issue-resolution + approval in deterministic order;
- timeline excludes field-level corrections;
- timeline exposes deterministic summary, not the raw operator note;
- empty timeline is `[]` not null;
- rollup available + counts for a remediated review-origin draft;
- rollup unavailable/zero + `structured_action_lineage_missing` for missing lineage;
- rollup action count/remediated/traceable match the 15H detail derivation;
- cross-tenant actions do not leak into the rollup;
- `latestRemediationActionAt` equals the max persisted action `createdAt`.

Regression: 15G (8), 15H (9), 15C (8), 14C (9), permission interceptor (68) all still green;
focused `*Stage15*,*Stage14*,ApiPermissionInterceptorPermissionTest` sweep = 155/155.

Frontend — `validation-review-draft-remediation-lineage.test.mjs` + `validation-review-draft-queue.test.mjs`
(37 combined): rollup type/render, limitation-code tokens, detail link preserved, timeline render + ordering,
explicit empty timeline state, `available=false`/error states, no raw/unsafe content. Full FE suite 246/246,
lint clean, `tsc --noEmit` clean, `npm run build` OK.

## Why this stays read-only

Every 15I value is a deterministic projection or aggregate of `OperatorAction` rows already written by the
audited OP-CAP-14C command path. Nothing here creates or mutates business/validation/draft state, calls a
connector, or trusts AI output as truth. It is purely a friendlier read surface over existing structured
lineage — consistent with OrderPilot's model: AI suggests, rules validate, humans approve, backend writes,
audit records.

## Verification

```
# apps/core-api
mvn -q compile
mvn -Dtest=ValidationReviewRemediationTimelineRollupStage15ITest test          # 9/9
mvn -Dtest='*Stage15*,*Stage14*,ApiPermissionInterceptorPermissionTest' test   # 155/155
# apps/web-dashboard
node --test tests/validation-review-draft-remediation-lineage.test.mjs tests/validation-review-draft-queue.test.mjs   # 37/37
node --test "tests/**/*.test.mjs"   # 246/246
npm run lint && npx tsc --noEmit && npm run build
```

## Next recommended slice

OP-CAP-15J — optional tenant-level remediation rollup tile (read-only) over a bounded recent window, reusing
the same structured batch derivation, if managers later need an at-a-glance cross-draft view. Still no write
path, still no free-text parsing.
