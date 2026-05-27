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
