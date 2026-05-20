# Stage 11D - Operator Substitute Approval + Quote Lifecycle Controls

## Objective

Stage 11D adds controlled operator approval and rejection for deterministic substitute candidates.

OrderPilot remains a secure transaction intelligence layer. Substitute candidates are not final quote or order lines until an operator explicitly approves them through backend command services. Internal quote approval in this stage does not create connector commands and does not execute ERP, 1C, accounting, warehouse, or other external writes.

## Lifecycle States

Draft quotes can move through these lifecycle statuses:

- `DRAFT`
- `NEEDS_REVIEW`
- `SUBSTITUTION_REVIEW`
- `READY_FOR_APPROVAL`
- `APPROVED`
- `REJECTED`
- `CANCELLED`

The lifecycle is recalculated by `QuoteLifecycleService` from line-level substitute decisions and open blocking validation issues. A quote with pending substitute decisions cannot become approved. A quote with all blocking substitute decisions resolved can move to `READY_FOR_APPROVAL`, then to internal `APPROVED` if policy allows it.

## Line Substitute Decisions

Draft quote lines expose substitute decision state:

- `NO_SUBSTITUTE_REQUIRED`
- `SUBSTITUTE_SUGGESTED`
- `SUBSTITUTE_APPROVAL_REQUIRED`
- `SUBSTITUTE_APPROVED`
- `SUBSTITUTE_REJECTED`
- `SUBSTITUTE_BLOCKED`
- `NO_SAFE_SUBSTITUTE_FOUND`

Line responses include the original resolved product, selected substitute product when approved, candidate list, reason code, decision actor, decision timestamp, and decision note.

## Operator Commands

Stage 11D adds backend command services and narrow REST endpoints for:

- approving a substitute candidate for a draft quote line
- rejecting a substitute candidate for a draft quote line
- resetting a substitute decision
- marking a quote ready for approval
- approving a quote internally
- rejecting or cancelling a quote with a reason

Business rules stay in service classes, not controllers.

## Deterministic Guardrails

The approval service blocks unsafe transitions:

- blocked customer substitutes cannot be approved
- substitutes from another tenant cannot be approved
- candidates not attached to the quote line cannot be approved
- high-risk substitutes require the existing tenant policy approval placeholder
- compatibility-unverified substitutes cannot be approved as safe
- quotes with pending substitute decisions or blocking validation issues cannot be approved
- internal quote approval does not create connector commands, sandbox executions, compensation plans, or external writes

All tenant-owned queries remain tenant-scoped.

## Audit Events

Every operator lifecycle decision emits an audit event:

- `SUBSTITUTE_CANDIDATE_APPROVED`
- `SUBSTITUTE_CANDIDATE_REJECTED`
- `SUBSTITUTE_DECISION_RESET`
- `QUOTE_MARKED_READY_FOR_APPROVAL`
- `QUOTE_APPROVED_INTERNAL`
- `QUOTE_REJECTED`
- `QUOTE_CANCELLED`

Audit metadata includes quote id, quote line id where relevant, original product id, substitute product id where relevant, actor id, reason or note, previous state, and new state.

## Validation Issue Handling

When an operator approves a safe substitute, related substitute review issues are resolved and the quote lifecycle is recalculated. When an operator rejects a candidate and no safe candidates remain, the line stays unresolved with `NO_SAFE_SUBSTITUTE_FOUND`.

## Non-Goals

- no autonomous substitute selection
- no quote or order external auto-conversion
- no connector command creation from substitute approval
- no ERP, 1C, accounting, or warehouse write
- no real AI provider call
- no UI redesign
