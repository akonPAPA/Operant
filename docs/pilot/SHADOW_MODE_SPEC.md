# Shadow Mode Specification

## Goal

Shadow mode lets OrderPilot process real-like or approved real pilot inputs while humans remain authoritative and external writes stay disabled.

The workflow is:

```text
real-like input
  -> OrderPilot prediction
  -> human comparison
  -> correction
  -> learning dataset
  -> confidence and rule improvement
```

## Workflow

1. Intake receives a pilot-approved document or message.
2. Core API stores the intake source and processing state under the correct tenant.
3. Advisory extraction predicts intent, document type, fields, line items, evidence, confidence, and warnings.
4. Deterministic validation compares the extraction against tenant mirror data and validation rules.
5. Internal workspace may group issues or draft internal quote/order records only when allowed by the current stage gate.
6. Human reviewer compares OrderPilot output with the correct operator decision or historical outcome.
7. Reviewer records corrections and exception categories.
8. Metrics are aggregated for pilot ROI and readiness reporting.
9. Product team uses the learning dataset to tune rules, thresholds, prompts, schemas, and reviewer workflow.

## What Counts As A Prediction

A prediction is any OrderPilot-generated advisory output, including:

- Intent classification.
- Document/message type.
- Customer match or customer hint.
- Product/SKU/OEM/alias match.
- Quantity and UOM normalization.
- Requested date or delivery need.
- Price, discount, margin, and currency interpretation.
- Inventory availability check.
- Substitute candidate.
- Validation issue or approval requirement.
- Exception category.
- Draft quote/order readiness recommendation.
- Confidence score.
- Prompt-injection or safety warning.

Predictions are not authority. They are inputs for human review and deterministic validation.

## What Counts As Human Correction

A human correction is any reviewer change, rejection, or confirmation that differs from OrderPilot's prediction or determines that a prediction is not safe to use.

Examples:

- Change detected intent from `UNKNOWN` to `RFQ`.
- Correct customer match.
- Correct SKU or product match.
- Correct quantity, UOM, date, currency, price, or discount.
- Reject a substitute candidate.
- Add a missing validation issue.
- Mark an exception category differently.
- Mark a draft as not ready.
- Flag unsupported document type.
- Flag unsafe request, prompt injection, or external-write attempt.

Human corrections are the authoritative pilot signal.

## What Gets Measured

Shadow mode must measure:

- Document/message volume.
- Automation candidate rate.
- Extraction confidence.
- Validation issue rate.
- Human correction rate.
- Field-level correction rate.
- Line-item correction rate.
- Exception category distribution.
- Cycle time before and after.
- Draft quote/order readiness rate.
- Substitution acceptance rate.
- Discount/margin issue rate.
- Estimated labor time saved.
- Safety events and blocked external-write attempts.

## Forbidden In Shadow Mode

Forbidden:

- Real LLM provider integration without a later approved stage.
- Real OCR provider integration without a later approved stage.
- Real ERP/1C/accounting/warehouse/payment writes.
- Customer-facing messages sent by OrderPilot.
- Inventory reservation, decrement, shipment, or invoice creation.
- Product, customer, price, discount, margin, inventory, quote, or order master-data overwrite from AI/chatbot/frontend/connector paths.
- AI output bypassing deterministic validation.
- Draft quote/order approval without human review.
- Production credentials in repo, frontend env, committed docs, or demo fixtures.
- Tenant data mixing.

## Draft Quote/Order Enablement

Internal draft quote/order creation can be enabled in shadow mode only when:

- The source item has a tenant context.
- Extraction result exists and remains advisory.
- Deterministic validation has run.
- High-risk validation issues and approval requirements are visible.
- Human reviewer is assigned.
- UI/API copy clearly says the draft is internal only.
- No external send, ERP write, warehouse write, inventory reservation, or customer notification is possible.
- Audit/operator timeline records are created for important workflow actions.

Draft creation should stay disabled or limited to fixtures if a design partner cannot provide human review coverage.

## Why External Writes Stay Disabled

External writes stay disabled because Stage 10A is about learning, measurement, and readiness rather than production action.

OrderPilot does not yet have the complete external-write safety system:

- Real authentication and RBAC/ABAC are not fully proven for pilot production.
- ChangeRequest model for external writes is not implemented.
- Transactional outbox and connector idempotency are not implemented.
- ERP/1C/accounting/warehouse connector rollback behavior is not proven.
- Human correction rates are not yet known on real client data.
- Tenant-specific data quality and exception distribution are not yet measured.

Until those controls exist, external writes remain out of scope.
