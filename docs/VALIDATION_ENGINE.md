# Validation Engine

Phase 5A turns advisory AI extraction results into deterministic validation results. AI suggests raw values; deterministic backend services decide whether a line can proceed, needs operator review, or is blocked until fixed.

Phase 5B connects those validation outcomes to operator review and internal draft command preparation. It does not bypass validation; it checks review status, unresolved validation issues, approval requirements, substitution safety, and tenant ownership before any draft quote/order command is prepared.

Phase 6A adds an operator workspace over the Phase 5B bridge. Operators can inspect validation issues, line-level checks, approval requirements, substitute candidates, audit timeline entries, and backend safety rejections before preparing internal draft commands.

Phase 6B adds tenant-scoped correction and override commands so operators can resolve review risks before draft preparation. Corrections update review/validation state through backend services and audit events; they do not let the UI write trusted master data directly.

Phase 6C improves review ergonomics by exposing deterministic product candidates, substitute risk cues, pending approvals, correction history, audit timeline, blocking reasons, and a safe internal draft preview before draft preparation.

Phase 6D adds manager approval decisions and a single backend-authoritative draft readiness evaluator. Review detail, draft preview, draft quote preparation, and draft order preparation now report and enforce the same readiness state.

## Authority Boundary

Validation may create validation runs, line checks, issues, substitute candidates, approval requirements, review cases, internal draft command preparation records, and audit events. It must not create approved quotes, approved orders, inventory movements, price changes, customer changes, product changes, or ERP writes.

Future quote/order mutations must go through typed backend command services with tenant policy, deterministic validation, transactions, audit, and approval gates.

## Product Matching Order

Product matching is deterministic and tenant-scoped:

1. Exact internal SKU match.
2. Normalized `ProductAlias` match.
3. `OEMReference` match.
4. Fallback candidate list from deterministic catalog search.

Unknown or ambiguous products create validation issues and route to operator review or blocked status depending on severity.

## UOM Normalization

Basic aliases are normalized before pricing and inventory checks:

- `EA`, `EACH`, `PCS`, `PIECE` -> `EA`
- `BOX`, `BX` -> `BOX`
- `SET` -> `SET`
- `KIT` -> `KIT`

Unknown UOM values create a validation issue and a review requirement. They are hard-blocked for draft preparation until corrected or explicitly overridden through the validation review flow; they are not treated as manager-approval-backed draft blockers.

## Approval Rules

Review or approval is required for:

- Low-confidence extraction results or line items.
- Unknown UOM values, which require correction or explicit audited override before draft preparation.
- Ambiguous product or customer matches.
- High-risk or customer-blocked substitutes.
- Requested discounts above configured `DiscountRule` thresholds.
- Calculated margins below configured `MarginRule` approval thresholds.

Critical margin violations and missing deterministic matches can block routing until fixed.

## Review And Draft Preparation

Phase 5B review cases reuse the existing exception/operator review workflow. A review case can move through `PENDING_REVIEW`, `APPROVED_FOR_DRAFT`, `REJECTED`, `NEEDS_CORRECTION`, or `BLOCKED` style states according to the existing workspace model.

Draft quote/order preparation is internal only. It can proceed only when validation is low-risk or the review case is approved for draft preparation. The preparation gate rejects:

- Blocked substitutes.
- Unresolved unknown UOM issues.
- Unresolved ambiguous or missing product matches.
- Discount or margin approval requirements before review approval.
- Low-confidence extraction cases before review approval.

Prepared drafts are not external writes. ERP writes, connector commands, inventory reservations, and external order placement remain out of scope until an explicit later connector/write stage.

## Workspace UI Safety

The Phase 6A UI must make unresolved risk visible:

- Blocked substitutes.
- Unknown UOM values.
- Ambiguous product matches.
- Margin and discount approval violations.
- Low-confidence fields or line items.
- Open approval requirements.

The UI may disable obvious unsafe actions, but backend validation and review gates remain authoritative. If the frontend cannot determine risk safely, it must submit through the backend command endpoint and display the backend rejection reason.

## Correction And Override Lifecycle

Validation issues can move through these review statuses:

- `OPEN`: unresolved and still considered by draft preparation gates.
- `CORRECTED`: resolved by a backend correction command such as UOM correction, quantity correction, or product mapping.
- `ACKNOWLEDGED`: non-blocking issue accepted by an operator for review context only.
- `OVERRIDDEN`: issue accepted with an explicit reason; risky overrides are audited and remain visible.

Correction commands may update extracted-line normalized values, UOM normalization results, product match results, substitute candidate status, validation issue status, and related approval requirement status. They never create products, update product aliases, change inventory, change price rules, mutate customer master data, execute connector commands, or write to ERP.

Draft preparation remains blocked while `OPEN` blocking issues, unresolved product matches, blocked substitutes, or open approval requirements remain. Blocked responses include a machine-readable issue code, severity, reason, and suggested correction action.

## Approval Decisions And Final Readiness

Manager approval requirements can move through these states:

- `OPEN`: pending manager decision and blocking draft preparation.
- `APPROVED`: accepted by a manager or authorized reviewer with a decision reason when the approval is risky.
- `REJECTED`: rejected with a required reason and blocking draft preparation.
- `CORRECTED` or `OVERRIDDEN`: resolved by a backend correction or explicit audited override.

The draft readiness evaluator returns:

- `draftPreparationAllowed`: true only when all backend gates pass.
- `readinessStatus`: `READY`, `BLOCKED`, or `PENDING_OR_REJECTED_APPROVAL`.
- `blockingReasons`: structured issue code, severity, reason, and suggested correction action.
- `pendingApprovals`, `rejectedApprovals`, and `resolvedApprovals`.
- `nextRequiredActions`: deterministic next steps for the operator.

Readiness checks unresolved blocking validation issues, pending or rejected required approvals, risky selected substitutes without approval, blocked substitutes, unresolved product mapping, unresolved UOM/quantity corrections, discount/margin approval state, and review-case approval state where the workflow requires it.

Review detail, draft preview, draft quote preparation, and draft order preparation use this same evaluator. If readiness is blocked, preparation returns `409 DRAFT_PREPARATION_BLOCKED` with the evaluator's structured blocking reasons.

## Candidate Selection And Draft Preview

Product candidate selection uses existing `ProductMatchResult` data. When validation stored candidate product IDs, review detail exposes tenant-scoped product candidate labels so the operator can select a product without pasting UUIDs. If no candidates exist, the UI may fall back to a product ID mapping command, but the backend still validates tenant ownership.

Substitute candidate selection uses existing substitute candidate records and exposes risk, status, approval, stock, and margin cues where available. Customer-blocked substitutes remain blocked and cannot be silently selected.

Draft preview is generated from existing validation rows and extracted line items. It shows line quantity, UOM, selected product/substitute, unit price, stock status, margin/discount warnings, validation status, and unresolved blockers. Preview generation is audited and does not create drafts, reserve inventory, execute connector commands, or write to ERP.

## Substitution Ranking

Substitute candidates are ranked with deterministic signals:

- Existing substitute relation.
- Compatibility records.
- Available stock.
- Customer substitution preferences and blocked substitutes.
- Risk level and margin status.

Blocked substitutes are not marked safe. Fuzzy, vector, or embedding-based candidate providers are intentionally out of scope for Phase 5A and should be added behind a deterministic review interface later.

## Routing Recommendations

- `AUTO_READY_DRAFT_ALLOWED`: no warning/error/critical issues and no approval requirements.
- `NEEDS_OPERATOR_REVIEW`: warning issues or approval requirements exist.
- `BLOCKED_UNTIL_FIXED`: error or critical issues exist.

Approved quote/order creation, inventory reservation, connector execution, and ERP writes remain out of scope for Phase 6 validation review.
