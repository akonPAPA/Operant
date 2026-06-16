# OP-CAP-15H — Draft Remediation Lineage Detail

Adds a narrow, **read-only** drill-down that makes the OP-CAP-15G queue remediation *summary* explainable.
From a review-origin draft an operator can open a **remediation lineage detail** and see, per draft line,
the exact structured operator-action lineage behind the draft — corrections, issue resolutions and
approvals — plus run-scoped actions that could not be attached to a draft line, and machine-readable
limitations explaining any gap.

No new write path, no new command behavior, no AI-worker change, no migration. Derived strictly from
structured, tenant-scoped records with stable ids (the same OP-CAP-14C `OperatorAction` lineage 15G counts).

## Purpose

15G answered *"how much remediation happened before these drafts were created?"* at the queue level.
15H answers the follow-up *"show me exactly what"* for one draft:

1. What draft is this, and which validation run/review did it originate from?
2. Which draft lines are traceable to a validated source line?
3. For each traced line — was there a correction, an issue resolution, an approval? With which ids/types/status?
4. Which structured actions exist for the review but could not be attached to a draft line?
5. Which draft lines are missing source lineage, and why is lineage partial?

## Endpoint

```
GET /api/v1/validations/review-drafts/{draftKind}/{draftId}/remediation-lineage
```

- `draftKind` — `QUOTE` or `ORDER` (any other value → `400`).
- Permission: `VALIDATION_READ` (auto-covered by the existing `/api/v1/validations` GET prefix rule —
  same guard as the 15C/15G queue, no new permission).
- Tenant resolved server-side (`X-Tenant-Id` → `TenantContext`); a missing or foreign-tenant draft → bounded `404`.
- Read-only: creates no draft, emits no audit, writes nothing, triggers no external/ERP/1C/connector action.

Frontend route: `/workspace/review-drafts/{draftKind}/{draftId}/remediation-lineage`, linked from each row of
the existing `/workspace/review-drafts` queue ("Remediation lineage"). Server-rendered, no client state.

## DTO summary

New records in `ValidationReviewCommandDtos`:

```java
record ValidationReviewDraftRemediationLineageDetail(
    String draftKind, UUID draftId, UUID validationRunId, UUID sourceExceptionCaseId,
    boolean available, List<String> limitations,
    int draftLineCount, int traceableDraftLineCount, int remediatedDraftLineCount,
    int correctionActionCount, int issueResolutionActionCount, int approvalActionCount,
    List<ValidationReviewDraftRemediationLineageLine> lines,
    List<ValidationReviewDraftRemediationLineageUnattachedAction> unattachedActions,
    String workspacePath, String reviewPath, String externalExecution) {}   // externalExecution = "DISABLED"

record ValidationReviewDraftRemediationLineageLine(
    UUID draftLineId, UUID sourceLineItemId, boolean sourceLineAvailable, int lineNumber,
    String sku, String description, BigDecimal quantity, String uom,
    List<...Action> correctionActions, List<...Action> issueResolutionActions, List<...Action> approvalActions,
    List<String> limitations) {}

record ValidationReviewDraftRemediationLineageAction(
    UUID operatorActionId, String actionType, String targetType, UUID targetId,
    UUID relatedLineItemId, UUID relatedIssueId, UUID relatedApprovalRequirementId,
    String status, Instant createdAt, String summary) {}   // status = related issue/approval domain status

record ValidationReviewDraftRemediationLineageUnattachedAction(
    UUID operatorActionId, String actionType, String targetType, UUID targetId,
    String category, String limitation, Instant createdAt, String summary) {}
```

`summary` is the deterministic backend-authored `OperatorAction.message` (e.g. "Operator corrected an
advisory extracted line item value") — never a raw operator note, raw AI payload, prompt, or secret.

## Derivation rules (structured evidence only)

Service: `ValidationReviewDraftRemediationLineageService` (narrow, read-only). All joins are by **stable
ids**, never by text — the same OP-CAP-14C tokens 15G uses:

- **Draft lines** load via `findByTenantIdAndDraft{Quote,Order}Id`; each line's
  `sourceExtractedLineItemId` is its trace to a validated source line.
- **Corrections** — `OperatorAction` where `targetType=EXTRACTED_LINE_ITEM`,
  `actionType=VALIDATION_REVIEW_LINE_ITEM_CORRECTED`, `targetId ∈ drafted source line ids` → attached to that line.
- **Issue resolutions** — `OperatorAction` where `targetType=VALIDATION_ISSUE`,
  `actionType=VALIDATION_REVIEW_ISSUE_RESOLVED`, on an issue of the draft's run whose `extractedLineItemId`
  is a drafted source line → attached; `status` = the `ValidationIssue` status.
- **Approvals** — `OperatorAction` where `targetType=APPROVAL_REQUIREMENT`,
  `actionType=VALIDATION_REVIEW_APPROVAL_REQUESTED`, on an `ApprovalRequirement` of the draft's run whose
  `extractedLineItemId` is a drafted source line → attached; `status` = the `ApprovalRequirement` status.
- **remediatedDraftLineCount** — distinct draft lines with ≥1 attached action.
- **Unattached** — issue-resolution / approval actions that exist for the run but whose issue/approval line
  is `null` or not a drafted source line (e.g. a resolution on a non-drafted line, or a **run-level
  approval**). Surfaced separately with a `category` and a limitation token.

### Limitation tokens (stable, machine-readable)

- `structured_action_lineage_missing` (reused from 15G) — no draft line carries a source line id.
- `draft_line_source_line_missing` — at least one draft line has no source line id (also set per-line).
- `draft_not_review_origin` — the draft has no originating validation run; `available=false`.
- `unattached_issue_resolution_action` / `unattached_approval_action` — run-scoped actions exist that map
  to no drafted line.

`available` is `false` **only** when the detail cannot be produced at all (draft is not review-origin).
Partial lineage stays `available=true` with limitation tokens, per the explainability goal.

## Relation to OP-CAP-15G

15G computes the per-row *counts* on the queue for the whole page via a fixed set of bounded batch queries.
15H reuses the **same structured tokens, repositories and id-join rules** but for a **single draft**, and
additionally materializes the individual actions per line and the unattached set. The 15G
`ValidationReviewDraftQueueItem.remediationSummary` field and its derivation are **unchanged**; 15H only
adds a detail endpoint and a queue link. 15H is read-side only — it consumes the real `OperatorAction`
lineage written by the OP-CAP-14C command service (proven by integration tests, not hand-built DTOs).

## Security / tenant notes

- Every load is tenant-scoped (`TenantContext.requireTenantId()` + `...AndTenantId` / tenant-keyed batch
  queries). A foreign-tenant draft is a bounded `404`; cross-tenant actions/issues/approvals are invisible.
- No raw operator-note content, raw AI payload, prompt, document body, secret or stack trace is exposed —
  ids, enum/token strings, deterministic backend messages and numeric counts only.
- UI renders plain text (no `dangerouslySetInnerHTML`); the detail is informational, never a security control.
- Read-only: no mutation, no audit event, no change to 15A/15B create-endpoint authority, external execution
  reported as `DISABLED`.

## Performance / N+1 notes

For one draft the service runs a **fixed** number of bounded, tenant-scoped queries (no per-line query):
draft + draft lines, then five batch reads keyed by id sets — corrections (`IN` source line ids), issues
(`IN` run id), issue-resolution actions (`IN` issue ids), approvals (`IN` run id), approval actions (`IN`
approval ids). Empty id sets short-circuit. Aggregation is in-memory.

## Limitations

- Field-level corrections (`VALIDATION_REVIEW_FIELD_CORRECTED`) target a field id (not line-scoped) and are
  not part of line lineage — consistent with 15G.
- Unattached **corrections** on run lines that were not drafted are not enumerated (that would require
  loading the run's full extracted-line-item set); unattached coverage is issue-resolution and approval
  actions, which are run-scoped by id. Documented and bounded by design.
- Lineage is id-linkage within the draft's source review, not a strict temporal "fixed before draft" proof
  (timestamps are not used as evidence, by rule), same caveat as 15G.

## Verification

Backend (`apps/core-api`):

```
mvn -q compile
mvn -Dtest=ValidationReviewDraftRemediationLineageStage15HTest test          # 9/9
mvn -Dtest=ValidationReviewDraftQueueRemediationStage15GTest test            # 8/8 (15G regression)
mvn -Dtest=ValidationReviewCommandStage14CTest test                          # 9/9 (14C action source)
mvn -Dtest=ApiPermissionInterceptorPermissionTest test                       # 68/68 (+2 for 15H route)
mvn -Dtest='*Stage15*,*Stage14*' test                                        # 78/78 focused 14/15 sweep
```

Frontend (`apps/web-dashboard`):

```
node --test tests/validation-review-draft-remediation-lineage.test.mjs tests/validation-review-draft-queue.test.mjs   # 29/29
node --test "tests/**/*.test.mjs"   # 238/238
npm run lint                        # clean
npx tsc --noEmit                    # clean
npm run build                       # /workspace/review-drafts/[draftKind]/[draftId]/remediation-lineage registered
```

## Next recommended slice

OP-CAP-15I — operator action timeline polish: attach the bounded action `summary`/`createdAt` already
returned by 15H into a compact per-line timeline view (still read-only), and/or the tenant-level remediation
rollup tile previously sketched (read-only analytics over the same structured batch derivation). No new
write path, no free-text parsing.
