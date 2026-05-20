# Correction Tracking Contract

Stage: Stage 10B mock-only shadow mode.

Human corrections compare advisory/mock predictions against operator review. They do not approve external actions, update master data, send messages, submit quotes, submit orders, or write to ERP/1C/accounting/warehouse systems.

## Shadow Run Contract

Create a shadow run when OrderPilot wants to record what a mock advisory system predicted for a source object.

Required fields:

- `sourceType`: `INBOUND_DOCUMENT`, `CHANNEL_MESSAGE`, `DRAFT_QUOTE`, `DRAFT_ORDER`, or `VALIDATION_CASE`
- `sourceId`: source object UUID
- `predictionType`: `EXTRACTION`, `VALIDATION`, `SUBSTITUTION`, `PRICING`, `INVENTORY`, `QUOTE_DRAFT`, or `ORDER_DRAFT`
- `predictionPayloadJson`: JSON payload as text
- `confidenceScore`: optional score from 0.0 to 1.0

Server-owned fields:

- `tenantId` from `TenantContext.requireTenantId()`
- `providerMode` fixed to `MOCK_ONLY`
- `status` initially `RECORDED`
- `createdAt`
- `reviewedAt`

## Human Correction Contract

Record a human correction against a shadow run after review.

Correction types:

- `ACCEPTED`
- `FIELD_CORRECTED`
- `LINE_CORRECTED`
- `VALIDATION_OVERRIDDEN`
- `SUBSTITUTION_ACCEPTED`
- `SUBSTITUTION_REJECTED`
- `PRICE_CORRECTED`
- `INVENTORY_CORRECTED`
- `REJECTED`
- `OTHER`

Payload fields:

- `correctedByUserId`: nullable until production auth/RBAC is proven end to end
- `beforePayloadJson`: advisory payload or relevant excerpt before review
- `afterPayloadJson`: human-reviewed payload or relevant excerpt after review
- `correctionReason`: optional human-readable explanation

Status mapping:

- `ACCEPTED` and `SUBSTITUTION_ACCEPTED` mark the shadow run `ACCEPTED`.
- `REJECTED` and `SUBSTITUTION_REJECTED` mark the shadow run `REJECTED`.
- Other correction types mark the shadow run `CORRECTED`.

## Metrics Meaning

`humanCorrectionRate` is `corrected_count / reviewed_shadow_runs`. It measures how often human review materially changed advisory output during pilot readiness checks. It is not production ROI proof.

`averageConfidence` is the average confidence of recorded shadow runs with confidence values. It is not a calibrated production model score.

## Non-goals

- No real AI provider integration.
- No provider secret handling.
- No external connector write mode.
- No ChangeRequest or outbox execution.
- No production dashboard or UI redesign.
