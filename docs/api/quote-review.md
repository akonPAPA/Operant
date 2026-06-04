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
