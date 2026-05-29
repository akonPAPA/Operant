# Quote-to-Order Transaction Core

Stage 12A implements the RFQ-to-draft-quote business path as backend domain behavior, not as a demo script.

## Business Flow

1. A tenant-scoped RFQ command is submitted to `POST /api/v1/quotes/from-rfq`.
2. `CustomerResolutionService` resolves the customer by external reference, account code, legal name, or display name.
3. `ProductResolutionService` resolves each requested item by exact SKU, customer/global alias, or OEM reference.
4. `QuoteInventoryValidationService` checks the latest tenant-scoped inventory snapshot for the requested product and location.
5. `PricingService` deterministically selects the most specific active price rule by customer, location, priority, quantity, and UOM.
6. `MarginValidationService` evaluates requested discount against product cost and configured margin rules.
7. `SubstitutionService` returns deterministic substitute candidates for unavailable products.
8. `ApprovalPolicyService` creates quote approval requests for margin, discount, or risky substitute conditions.
9. `QuoteDraftService` persists the draft quote, lines, validation issues, approval requests, and audit events in one backend transaction.

## Deterministic Validation Rules

- Unknown customer creates `CUSTOMER_NOT_RESOLVED` and prevents auto-ready handling.
- Unknown product creates `PRODUCT_NOT_RESOLVED`.
- Products resolve by exact normalized SKU, exact alias, or exact OEM reference only.
- Missing price creates `PRICE_NOT_RESOLVED`.
- Missing stock snapshot creates `STOCK_NOT_EVALUATED`.
- Insufficient stock creates `INSUFFICIENT_STOCK` and triggers substitute lookup.
- Requested discounts are evaluated against active `DiscountRule` rows.
- Margin is computed from net unit price after requested discount and product cost.

## Approval Rules

- `MARGIN_BELOW_GUARDRAIL` and `MARGIN_APPROVAL_REQUIRED` create `quote_approval_request` rows.
- `DISCOUNT_EXCEEDS_RULE` and `DISCOUNT_APPROVAL_REQUIRED` create approval requests.
- `SUBSTITUTE_REQUIRES_APPROVAL` and `SUBSTITUTE_BLOCKED_FOR_CUSTOMER` create approval requests.
- Blocked substitutes are returned as blocked/risky candidates and are not auto-approved.

## Audit Events

The transaction core emits:

- `DRAFT_QUOTE_RFQ_CREATE_REQUESTED`
- `DRAFT_QUOTE_CREATED`
- `DRAFT_QUOTE_RFQ_IDEMPOTENT_REPLAY` when an idempotency key replays an existing quote

Audit metadata explicitly keeps external execution disabled.

## API

`POST /api/v1/quotes/from-rfq`

```json
{
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "actorId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "actorRole": "OPERATOR",
  "customerExternalRef": "CUST-1",
  "customerName": null,
  "requestedItems": [
    {
      "rawSkuOrAlias": "BRK-001",
      "description": "Front brake pads",
      "quantity": 2,
      "uom": "EA"
    }
  ],
  "requestedLocation": "ALM",
  "requestedDiscountPercent": 0,
  "idempotencyKey": "rfq-001"
}
```

The response includes `draftQuoteId`, `status`, `resolvedCustomer`, quote `lines`, `validationIssues`, `substituteCandidates`, `approvalRequired`, `approvalReasons`, `auditCorrelationId`, and `approvalRequests`.

## Known Limitations

- Stage 12A creates internal draft quotes only.
- It does not create final orders.
- It does not reserve or mutate real inventory.
- It does not execute connectors, create ERP/1C writes, or call external systems.
- Approval request decision workflow is represented by `quote_approval_request`; final approval actions remain a later stage.
- AI, bot, and frontend surfaces must continue to call backend APIs instead of writing directly to the database.

## Stage 12B Approval State Machine

Stage 12B adds an operator-controlled quote lifecycle around the Stage 12A draft transaction. Controllers expose commands, but `QuoteApprovalStateMachineService` owns the business rules, tenant checks, audit events, and internal conversion boundary.

Lifecycle states used by the Stage 12B command layer:

- `DRAFT`
- `PENDING_APPROVAL`
- `APPROVED`
- `REJECTED`
- `CHANGES_REQUESTED`
- `EXPIRED`
- `CONVERTED_TO_INTERNAL_ORDER`

Approval decisions are recorded in `quote_approval_decision` with tenant id, quote id, optional approval request id, decision, comment, actor, decision timestamp, previous/new quote status, resolved reasons, blocking reasons, and audit correlation id. Open approval requests are marked with their decision metadata. Invalid transitions return `QUOTE_LIFECYCLE_TRANSITION_BLOCKED`.

Approval rules:

- Quotes with unresolved hard blocking validation issues cannot become `APPROVED`.
- Approval-resolvable policy warnings such as `MARGIN_APPROVAL_REQUIRED` and `DISCOUNT_APPROVAL_REQUIRED` are resolved only by an explicit `APPROVE` decision.
- Hard margin violations, unresolved product/customer/price/stock issues, risky substitute blockers, rejected quotes, change-requested quotes, and already converted quotes cannot be converted.
- Converted quotes cannot be approved or rejected again.
- Every approval, rejection, change request, conversion, or blocked conversion emits audit evidence.

## Stage 12B API

`GET /api/v1/quotes/{quoteId}/approval-state`

Returns quote status, `approvalRequired`, blocking issues, approval reasons, approval requests, latest approval decision, internal draft order boundary id, ChangeRequest id if one is ever attached, external execution status, and audit correlation id.

`POST /api/v1/quotes/{quoteId}/approve`

```json
{
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "actorId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "actorRole": "OPERATOR",
  "reason": "Discount approved by operator",
  "idempotencyKey": "approve-quote-001"
}
```

`POST /api/v1/quotes/{quoteId}/reject`

`POST /api/v1/quotes/{quoteId}/request-changes`

Reject and request-changes commands use the same request shape. Request changes requires a reason/comment.

`POST /api/v1/quotes/{quoteId}/convert-to-internal-order`

Only an `APPROVED` quote can create the internal draft order boundary. The result includes `internalDraftOrderId`, optional `changeRequestId`, `externalExecutionStatus`, and `auditCorrelationId`.

## Stage 12B Conversion Boundary

The conversion boundary is internal-only. Stage 12B creates `quote_internal_order_boundary` as an internal draft order candidate and moves the quote to `CONVERTED_TO_INTERNAL_ORDER`. It does not call connectors, does not write ERP/1C/accounting/warehouse systems, does not reserve or mutate inventory, and does not report an external send as successful. `externalExecutionStatus` remains `EXTERNAL_EXECUTION_DISABLED`.

## Stage 12B Audit Events

- `quote.approved`
- `quote.rejected`
- `quote.changes_requested`
- `quote.converted_to_internal_order`
- `quote.conversion_blocked`
- `approval.decision.recorded`

Metadata includes tenant id, quote id, previous/new status, decision, reason, resolved reasons, blocking reasons, actor through the audit event, correlation id, and external execution disabled status.

## Stage 12B Acceptance Criteria

- Backend enforces lifecycle transitions and tenant scope.
- API endpoints are real service-backed commands.
- Blocking validation issues prevent approval.
- Invalid transitions return structured errors.
- Conversion is approved-only, idempotent, audited, and internal-only.
- Dashboard approval buttons call backend APIs and show status, reasons, blockers, result, audit correlation, and disabled external execution.
- No production connector, ERP/1C write, external inventory mutation, autonomous AI action, raw secret, direct frontend/AI/bot database write, PR, tag, commit, or push is part of this stage.

## Stage 12B Test Evidence Commands

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn test

cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm.cmd run lint
npm.cmd test
npm.cmd run build

cd C:\OrderPilot\OrderPilot-Core
git diff --check
git status --short
```
