# Stage 10B - Mock-only Shadow Mode and Pilot Metrics Skeleton

Status: implemented as a backend/mock contract skeleton.

Stage 10B adds the first safe pilot/shadow-mode surface for comparing advisory predictions against human review. It is intentionally mock-only and does not enable production AI providers or external connector writes.

## What Exists

- `POST /api/v1/pilot/shadow-runs` records one tenant-scoped mock/advisory prediction.
- `GET /api/v1/pilot/shadow-runs` lists tenant-scoped shadow runs, with optional `sourceType` and `status` filters.
- `POST /api/v1/pilot/shadow-runs/{id}/corrections` records a tenant-scoped human correction or acceptance.
- `GET /api/v1/pilot/metrics` returns pilot-readiness aggregate metrics.
- `apps/ai-worker` includes a mock shadow-mode fixture that emits advisory payloads only.

## Safety Boundaries

- Stage 10B is mock-only.
- AI output remains advisory only.
- No OpenAI, Anthropic, Azure, or other real provider integration exists.
- No production AI provider key is required.
- No ERP, 1C, accounting, warehouse, email, bot reply, quote submission, or order submission write path is enabled.
- Frontend, ai-worker, bot, and connector code must not write business or master tables directly.
- Shadow-mode records are pilot-readiness evidence, not authority for customer, product, inventory, pricing, quote, or order data.

## Data Model

`shadow_run` records advisory predictions:

- tenant-scoped via `tenant_id`
- source reference via `source_type` and `source_id`
- prediction purpose via `prediction_type`
- enforced mock provider mode via `provider_mode = MOCK_ONLY`
- payload in `prediction_payload_json`
- review lifecycle via `RECORDED`, `ACCEPTED`, `CORRECTED`, or `REJECTED`

`human_correction` records human review outcomes:

- tenant-scoped via `tenant_id`
- linked to `shadow_run_id`
- optional `corrected_by_user_id`
- correction category and before/after payloads
- reason text when available

## Metrics

The metrics endpoint exposes:

- `total_shadow_runs`
- `reviewed_shadow_runs`
- `accepted_count`
- `corrected_count`
- `rejected_count`
- `human_correction_rate`
- `average_confidence`
- `exception_category_counts`
- `prediction_type_breakdown`
- `correction_type_breakdown`

These are pilot-readiness metrics. They are not production ROI proof and should not be used as customer-facing performance claims until a controlled pilot, production auth/RBAC proof, real-provider safety harness, and external-write controls exist.

## Audit Compatibility

The service emits audit events for:

- `PILOT_SHADOW_RUN_RECORDED`
- `PILOT_HUMAN_CORRECTION_RECORDED`

These events preserve the existing OrderPilot posture: important pilot actions are traceable while business/master-data mutation remains outside this stage.

## Remaining Blockers

- ChangeRequest model
- transactional outbox
- connector idempotency
- external-write rollback contracts
- production auth/RBAC proof
- real provider safety and secret-management harness
- production pilot dashboards/UI
