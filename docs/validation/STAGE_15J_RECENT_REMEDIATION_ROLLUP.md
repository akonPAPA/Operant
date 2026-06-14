# OP-CAP-15J — Recent Remediation Rollup Tile

A small, **read-only**, tenant-scoped at-a-glance summary over *recent* review-draft remediation activity for
the review-draft workspace. It reuses the exact structured per-draft derivation from OP-CAP-15G/15H/15I — no
new write path, no audit mutation, no external execution, no AI-generated truth, no raw notes/payload, no new
permission. It is a **bounded recent-window** tile, deliberately **not** a full tenant analytics platform and
not an audit redesign.

## Purpose

The queue already shows a per-row rollup (15I) and the detail page shows a per-line timeline (15I). 15J adds
the missing at-a-glance operational view so a manager/operator can answer, without opening every row:

- How many recent review-origin drafts have remediation lineage?
- How many recent draft lines are traceable / remediated?
- How many structured operator actions are attached?
- How many recent drafts still have lineage limitations?
- What is the latest remediation action timestamp in the bounded recent window?
- Are there recent drafts with unavailable lineage?

## Endpoint

```
GET /api/v1/validations/review-drafts/remediation-rollup?limit=50
```

- Permission: `VALIDATION_READ` (auto-covered by the existing `/api/v1/validations` GET prefix rule — same
  guard as the 15C/15G queue and 15H detail; no new permission, no new route family).
- Tenant resolved server-side (`X-Tenant-Id` → `TenantContext`); foreign-tenant drafts/actions never enter.
- `limit`: optional, **default 50**, clamped to **max 100**; `null`/`<=0` falls back to default (matches the
  existing queue `clampLimit` convention). A non-numeric `limit` is a `400` (Spring type mismatch), matching
  existing validation-API behavior.
- Read-only: no write, no audit event, no external/ERP/1C/connector execution (`externalExecution` = `DISABLED`).

## DTOs

```java
record ValidationReviewDraftRecentRemediationRollupResponse(
    int inspectedDraftCount,            // drafts examined in the recent window (both kinds)
    int reviewOriginDraftCount,         // inspected drafts originating from a validation review
    int lineageAvailableDraftCount,
    int lineageUnavailableDraftCount,   // includes non-review-origin drafts
    int draftLineCount,                 // lines across review-origin inspected drafts
    int traceableDraftLineCount,
    int remediatedDraftLineCount,
    int remediationActionCount,         // correction + issue-resolution + approval
    int correctionActionCount,
    int issueResolutionActionCount,
    int approvalActionCount,
    Instant latestRemediationActionAt,  // max structured-action timestamp in the window, or null
    List<String> limitationCodes,       // unique, sorted union of per-draft tokens
    List<ValidationReviewDraftRecentRemediationRollupItem> topLimitedDrafts,  // bounded (max 10), sorted
    int limit,
    String externalExecution) {}        // "DISABLED"

record ValidationReviewDraftRecentRemediationRollupItem(
    String draftKind, UUID draftId, UUID sourceValidationRunId,  // run is null for non-review-origin
    boolean remediationLineageAvailable,
    int remediationActionCount, int remediatedLineCount, int traceableLineCount,
    Instant latestRemediationActionAt,
    List<String> limitationCodes) {}
```

Lists are never null. `latestRemediationActionAt` is nullable only when no structured action exists.
`topLimitedDrafts` contains the inspected drafts that carry limitation codes, capped at 10, sorted by
`latestRemediationActionAt` desc (nulls last) then `draftId` for deterministic output.

## Query limits & derivation source

`ValidationReviewDraftQueryService.recentRemediationRollup(limit)`:

1. Fetches the most recent drafts of **both** kinds, each bounded by `limit`
   (`findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(0, limit))`), unifies them newest-first
   (then `draftId`), and caps to the `limit` window — the **inspected** set.
2. For the **review-origin subset** (drafts with a `sourceValidationRunId`), it reuses the *same*
   `buildRemediationDerivations(...)` helper that powers the 15G/15I queue-row summary+rollup — a fixed set of
   bounded batch queries (corrections by source line, issues by run, resolution actions by issue, approvals by
   run, approval actions by approval). No per-draft query, no N+1, no unbounded tenant-wide scan.
3. **Non-review-origin** drafts contribute an explicit *unavailable-lineage* entry
   (`remediationLineageAvailable=false`, zero counts, `draft_not_review_origin`) — never fabricated lineage.
4. Aggregates counts, takes the max action timestamp, and builds the unique sorted limitation union and the
   bounded `topLimitedDrafts`.

`draftLineCount`/`traceable`/`remediated`/action counts aggregate over the review-origin subset; the
controller only parses `limit` and returns the DTO (no controller-side aggregation).

## Frontend tile behavior

- `lib/validation-review-draft-queue-api.ts` — added the rollup response/item types,
  `remediationRollupPath(limit?)`, and `getReviewDraftRecentRemediationRollup(limit?)` (tenant-scoped GET,
  403/400 mapped to bounded messages).
- `app/(dashboard)/workspace/review-drafts/page.tsx` — a compact **"Recent remediation"** tile above the
  queue table: inspected drafts (with review-origin), lineage available/unavailable, remediated/traceable
  lines, structured action count (with correction/issue/approval split), latest action timestamp, and
  limitation codes as `<code>` tokens. The tile is fetched **in parallel** with the queue (`Promise.all`);
  a tile failure renders a deterministic error state and **never breaks the queue**. Explicit empty state when
  there is no recent activity. Server-rendered, no client effects, no unsafe HTML.

The existing 15I per-row rollup column and the 15H "Remediation lineage" link/detail (incl. the 15I timeline)
are unchanged.

## Security invariants

- **Tenant-scoped**: all fetches/derivations run under `TenantContext.requireTenantId()`; a regression test
  proves a foreign-tenant draft+action yields zero counts and a null latest timestamp.
- **Read-only**: no command, no audit write, no external execution; `externalExecution` is `DISABLED`.
- **No raw content**: only ids, enum/token strings, counts and timestamps — never raw operator notes, raw
  document/prompt text, or AI output. No actor id is exposed (domain exposes none safely).
- **Machine-readable limitations**: `structured_action_lineage_missing` (review-origin, no source lines) and
  `draft_not_review_origin` (non-review-origin) — both stable tokens reused from 15G/15H.
- **Permission**: unchanged `VALIDATION_READ` for GET; a new permission interceptor test covers the route.

## Performance constraints

- Bounded recent window (`limit` default 50, max 100); at most `2 × limit` draft rows are inspected then
  capped to `limit`.
- Reuses the fixed bounded batch-query derivation; no per-draft query, no N+1, no unbounded scan.
- Does not load raw documents or extraction payloads; does not recompute per-line detail (summary-level
  derivation is sufficient — the per-line lists are only built by the 15H detail endpoint).

## Limitations

- This is a **bounded recent-window** rollup, not a full tenant analytics system or audit redesign.
- Counts are structured id/timestamp linkage, **not** strict causal proof.
- Field-level corrections remain excluded from line lineage (not line-scoped), as in 15G/15H/15I.
- `latestRemediationActionAt` is the **max structured action timestamp** in the window, not necessarily the
  moment a draft was "fixed."
- `draftLineCount`/traceable/remediated aggregate over the review-origin inspected drafts; non-review-origin
  drafts count toward `inspectedDraftCount`/`lineageUnavailableDraftCount` only.
- H2 tests are used (per existing project convention); no new Postgres integration was required.

## Tests

Backend — `ValidationReviewRecentRemediationRollupStage15JTest` (8):
- empty tenant returns a zero/empty rollup (default limit, `DISABLED`);
- aggregates correction/issue/approval action and line counts for a review-origin draft;
- counts review-origin separately from inspected; non-review-origin draft is unavailable (no fake lineage);
- review-origin draft with no source line ids is unavailable with `structured_action_lineage_missing`;
- `latestRemediationActionAt` equals the max persisted structured remediation-action timestamp;
- foreign-tenant drafts/actions do not affect counts;
- `limit` is bounded and deterministic (clamp to max, fallback to default);
- `topLimitedDrafts` is bounded (max 10) and deterministically sorted.

Plus a new `ApiPermissionInterceptorPermissionTest` pair (route guarded by `VALIDATION_READ`). Regression:
15G/15H/15I/15C/14C all still green — focused `*Stage15*,*Stage14*,ApiPermissionInterceptorPermissionTest`
sweep = 165/165.

Frontend — `validation-review-draft-recent-remediation-rollup.test.mjs` (11): path helper, read-only/tenant
fetch + 403/400 mapping, DTO shape, parallel non-blocking fetch, tile counts + latest timestamp, limitation
code tokens, empty + error states, safety, and that the 15I row rollup + 15H/15I detail timeline still render.
Full FE suite 257/257, lint clean, `tsc --noEmit` clean, `npm run build` OK.

## Verification

```
# apps/core-api
mvn -q compile
mvn -Dtest=ValidationReviewRecentRemediationRollupStage15JTest test            # 8/8
mvn -Dtest=ApiPermissionInterceptorPermissionTest test                          # 70/70 (+2 for 15J route)
mvn -Dtest='*Stage15*,*Stage14*' test                                           # focused 14/15 sweep green
# apps/web-dashboard
node --test tests/validation-review-draft-recent-remediation-rollup.test.mjs tests/validation-review-draft-queue.test.mjs tests/validation-review-draft-remediation-lineage.test.mjs   # 48/48
node --test "tests/**/*.test.mjs"   # 257/257
npm run lint && npx tsc --noEmit && npm run build
```

## Next recommended slice

OP-CAP-15K — optional `since`/time-window filter or a small sparkline of remediation actions over the recent
window, only if managers need trend (not just totals). Still read-only, still structured-only, still bounded.
