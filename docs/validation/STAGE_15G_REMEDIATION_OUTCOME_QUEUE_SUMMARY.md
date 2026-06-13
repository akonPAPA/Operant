# OP-CAP-15G — Remediation Outcome Summary on the Review-Origin Draft Queue

Adds a compact, **read-only** remediation-lineage summary to each row of the OP-CAP-15C review-origin
draft queue (`GET /api/v1/validations/review-drafts`, page `/workspace/review-drafts`). It gives managers
lightweight visibility into how often review-origin drafts were produced after remediation work — derived
strictly from structured, tenant-scoped records with stable ids. No new write path, no new endpoint, no
new workflow.

## Purpose

Answer "how much remediation happened before these drafts were created?" without standing up analytics
infrastructure or trusting free text. Per draft, surface: how many lines the draft has, how many of those
lines have structured remediation evidence, and how many correction / issue-resolution / approval actions
are linked to the draft's source review.

## DTO shape

Existing `ValidationReviewDraftQueueItem` gains one optional field:

```java
record ValidationReviewDraftQueueItem(
    ...existing 15C fields...,
    ValidationReviewDraftRemediationSummary remediationSummary
) {}

record ValidationReviewDraftRemediationSummary(
    boolean available,
    int draftLineCount,
    int remediatedDraftLineCount,
    int correctionActionCount,
    int issueResolutionActionCount,
    int approvalActionCount,
    List<String> limitations   // e.g. ["structured_action_lineage_missing"]
) {}
```

## Derivation rules (structured evidence only)

All counts are keyed by **stable ids**, never by text:

- **Source lines**: `DraftQuoteLine.sourceExtractedLineItemId` / `DraftOrderLine.sourceExtractedLineItemId`
  link each drafted line to the validated extracted line item it came from.
- **correctionActionCount**: structured `OperatorAction`s with
  `targetType=EXTRACTED_LINE_ITEM`, `actionType=VALIDATION_REVIEW_LINE_ITEM_CORRECTED`, and
  `targetId ∈ this draft's source line ids`.
- **issueResolutionActionCount**: structured `OperatorAction`s with `targetType=VALIDATION_ISSUE`,
  `actionType=VALIDATION_REVIEW_ISSUE_RESOLVED`, whose target issue's `extractedLineItemId` is one of this
  draft's source lines (issue→line resolved from `ValidationIssue` rows of the draft's run).
- **approvalActionCount**: structured `OperatorAction`s with `targetType=APPROVAL_REQUIREMENT`,
  `actionType=VALIDATION_REVIEW_APPROVAL_REQUESTED`, on an `ApprovalRequirement` belonging to the draft's
  source run (line-linked to a source line, or run-level).
- **remediatedDraftLineCount**: distinct drafted source lines that have a line correction **or** an
  issue-resolution action (the clearest "this line was remediated" signal).
- **draftLineCount**: number of lines in the internal draft (existing grouped count).
- **available=false** + `limitations=["structured_action_lineage_missing"]`: when a draft's lines carry no
  source line ids, advanced lineage is not derivable, so only `draftLineCount` is meaningful.

These tokens (`EXTRACTED_LINE_ITEM`, `VALIDATION_ISSUE`, `APPROVAL_REQUIREMENT`,
`VALIDATION_REVIEW_*`) are exactly the structured values OP-CAP-14C writes on every `OperatorAction`.

### Explicitly not counted

Generic/`OTHER` actions, actions whose only relation is free-text mention, actions on lines/issues from a
**different run**, actions in a **different tenant**, and plain drafted lines with no linked action. An
already-drafted line is not, by itself, "remediated".

## Why free-text audit parsing is forbidden

Human-readable `OperatorAction.message` / audit text is unstable, locale-bound, and easy to misattribute.
Counting from it would manufacture analytics that look authoritative but aren't. Every count here is an
exact-id join over structured columns; if the structured linkage is missing we say so (`available=false`)
rather than guess.

## Tenant / security notes

- Every query is tenant-scoped (`TenantContext.requireTenantId()`); cross-tenant actions/issues/approvals
  are invisible. The queue's existing foreign-tenant invisibility, filters, sort, and limit clamp are
  unchanged.
- No raw operator-note content is exposed — only `operatorNotePresent` (15C) and numeric counts.
- Raw ids are not rendered as primary UI text; the summary is counts/booleans only and plain-text rendered
  (no `dangerouslySetInnerHTML`).
- Read-only: no mutation, no new audit events, no change to 15A/15B create-endpoint authority. UI summaries
  are informational, never a security control.

## Performance / N+1 notes

The summary for the whole page is built with a **fixed number of bounded batch queries**, regardless of
page size:

1. existing grouped line-count queries (quote + order);
2. batch `(draftId, sourceLineId)` projections (quote + order);
3. one `OperatorAction` IN-query for line corrections over all source lines;
4. one `ValidationIssue` IN-query over the page's run ids;
5. one `OperatorAction` IN-query for issue resolutions over those issue ids;
6. one `ApprovalRequirement` IN-query over the page's run ids;
7. one `OperatorAction` IN-query for approval actions over those approval ids.

Aggregation is in-memory after these batches. Empty id sets short-circuit (no `IN ()`). The queue stays
bounded (limit clamped to 100) and paginated.

## Limitations

- `remediatedDraftLineCount` reflects structured remediation evidence linked by id within the draft's
  source review; it does not prove strict temporal "fix happened before draft" ordering (timestamps are not
  used as evidence, by rule). In practice review-origin drafts are created after review work, but the metric
  is an id-linkage count, not a causal proof.
- Field-level corrections (`VALIDATION_REVIEW_FIELD_CORRECTED`) target a field id, which is not line-scoped,
  so they do not contribute to per-line remediation counts.
- Approvals counted at run level (no line) are attributed to the draft's review as a whole.
- One draft per source review (15A/15B) keeps run→draft attribution unambiguous.

## Future path if stronger lineage is needed

If precise causal lineage is later required, introduce a structured `draft_line_remediation` link record
(written at draft-create time, capturing the exact action/issue ids that unblocked each drafted line). The
queue summary would then read that table directly instead of reconstructing linkage — still read-only, but
exact and temporally ordered. That is a deliberate future slice, not part of 15G.

## Next recommended slice

OP-CAP-15H — tenant-level remediation rollup: a small read-only analytics tile (reusing the same structured
batch derivation) showing, across a bounded recent window, how many review-origin drafts were created and
what share had remediation lineage — no new write path, no free-text parsing.
