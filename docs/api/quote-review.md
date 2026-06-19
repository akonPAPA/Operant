# Quote Review API

All endpoints are tenant-scoped through `X-Tenant-Id`. Non-GET endpoints require review action permission and mutate only through backend command services.

## Queue

`GET /api/v1/quote-review/queue`

Filters: `status`, `sourceType`, `channel`, `customer`, `issueType`, `severity`, `reviewRequired`, `assignedTo`, `createdFrom`, `createdTo`.

Rows include quote id, conversion attempt id, source context, customer summary, line count, issue count, highest severity, quote status, created time, assigned operator if supported, and next required action.

## Detail

`GET /api/v1/quote-review/{quoteId}`

Returns quote header, status, source context, conversion attempt summary, source lines, draft quote lines, validation issues, proposed substitutes, pricing/margin/discount risk summary, approval requirements, audit timeline, and review-required reasons.

## Issue Commands

- `POST /api/v1/quote-review/{quoteId}/issues/{issueId}/resolve`
- `POST /api/v1/quote-review/{quoteId}/issues/{issueId}/reject`
- `POST /api/v1/quote-review/{quoteId}/issues/{issueId}/apply-fix`
- `POST /api/v1/quote-review/{quoteId}/issues/{issueId}/escalate`

Command payloads include `tenantId`, `actorId`, `actorRole`, `reasonCode`, and `note`. `apply-fix` also accepts `fixType` and `values`.

## Correction Commands

- `POST /api/v1/quote-review/{quoteId}/customer`
- `POST /api/v1/quote-review/{quoteId}/lines/{lineId}/correct`

Customer correction accepts `customerAccountId`. Line correction accepts controlled fields: `quantity`, `uom`, `productId`, `removeLine`, or `manualFollowUp`.

Corrections re-run relevant validation and return `QuoteReviewCommandResult` with previous/new status, validation issues, review reasons, approval flag, and validation summary.

## Substitute Commands

- `POST /api/v1/quote-review/{quoteId}/lines/{lineId}/substitutes/select`
- `POST /api/v1/quote-review/{quoteId}/lines/{lineId}/substitutes/reject`

Selection requires a compatible tenant-owned candidate. Blocked candidates are rejected. Risky candidates are routed to approval.

## Approval

Stage 12C does not approve quotes. It creates or preserves existing `quote_approval_request` records. Approval decisions continue through the Stage 12B quote approval endpoints under `/api/v1/quotes/{quoteId}`.

## Draft Assembly (OP-CAP-36)

`POST /api/v1/quote-review/{quoteId}/assemble-draft`

Assembles a safe draft quote candidate from the reviewed/validated quote. The
`DraftQuote` already exists (it is the entity under review); assembly is the
explicit operator step that gates on review readiness, recalculates
backend-owned state, and returns an operator-safe summary. No backend draft
assembly endpoint existed before this slice — a narrow command endpoint was
added over the existing service/entities; no new draft entity or migration was
required (`draft_quote.status` is an unconstrained column).

Request body — business intent only:

```json
{ "reasonCode": "OPERATOR_REVIEW", "note": "Validated by operator" }
```

`Idempotency-Key` header is required (same `IdempotencyService` convention as the
other quote-review commands: same key + same request replays the stored result;
a new key recalculates and re-summarises the same draft). Tenant comes from
`X-Tenant-Id`; actor is server-resolved via `RequestActorResolver`. The body must
not carry `tenantId`, `actorId`, `sourceId`, `status`, `draftStatus`,
`approvalRequired`, `riskLevel`, `margin`, `stock`, totals, or audit/internal IDs;
such fields are ignored on deserialization (malicious-override test proven).

Backend ownership and rules:

- Readiness gate (`QuoteLifecycleService.requireReadyForApproval`): assembly is
  blocked (`409 QUOTE_LIFECYCLE_TRANSITION_BLOCKED`) while any open blocking
  validation issue, pending substitute decision, or rejected/blocked substitute
  remains. The frontend cannot assert resolution — backend derives it from stored
  review/line/substitute/issue data.
- Terminal quotes (`APPROVED`/`REJECTED`/`CHANGES_REQUESTED`/`EXPIRED`/
  `CONVERTED_TO_INTERNAL_ORDER`) cannot be assembled (`409`).
- Draft status: `DRAFT_ASSEMBLED` when no open approval request remains;
  `PENDING_APPROVAL` when approval is still required. The downstream Stage 12B
  approval state machine accepts either non-terminal status, so assembly never
  strands a quote; external order conversion still requires `APPROVED`.
- Idempotent recalculation: re-assembling an already-assembled (non-terminal)
  quote re-derives the summary from current stored data.
- Audit: emits `QUOTE_DRAFT_ASSEMBLED` (ids + previous/new status + reason/note +
  validation summary; `externalExecution=DISABLED`).
- No outbox / ChangeRequest / ERP/1C write is triggered in this slice.

Response — `QuoteDraftSummary` (operator-safe, backend-calculated only):
`quoteId` (public workflow handle), `quoteNumber`, `draftStatus`, `customer`
(account id/display/resolution), `currency`, `subtotalAmount`, `discountAmount`,
`totalAmount`, `marginPercent`, `lineCount`, `unresolvedBlockingIssueCount`,
`warningCount`, `stockWarningCount`, `approvalRequired`, `riskLevel`,
`marginStatus`, `validationSummary`, `nextAction`, `operatorMessage`,
`externalExecution` (`DISABLED`), `assembledAt`. It does not expose `tenantId`,
`actorId`, `createdBy`/`approvedBy`, `sourceId`, `auditEventIds`, or other raw
internal IDs.
