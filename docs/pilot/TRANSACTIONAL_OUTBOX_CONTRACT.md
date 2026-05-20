# Transactional Outbox Contract

Stage: Stage 10C internal-only skeleton.

`outbox_event` durably records internal domain events that future connector workers can consume after a later stage adds safe execution controls.

## Stage 10C Boundary

- Outbox events are internal-only.
- No real connector dispatcher is implemented.
- No ERP, 1C, accounting, warehouse, CRM, customer-message, or payment system is called.
- Events may be inspected by local/demo tooling, but they must not be treated as external delivery proof.

## Fields

- `id`
- `tenant_id`
- `aggregate_type`
- `aggregate_id`
- `event_type`
- `payload_json`
- `status`
- `created_at`
- `published_at`
- `attempt_count`
- `last_error`

## Event Types

- `CHANGE_REQUEST_CREATED`
- `CHANGE_REQUEST_VALIDATED`
- `CHANGE_REQUEST_VALIDATION_FAILED`
- `CHANGE_REQUEST_APPROVED`
- `CHANGE_REQUEST_REJECTED`
- `CHANGE_REQUEST_EXTERNAL_EXECUTION_DISABLED`

## Status Values

- `PENDING`
- `PUBLISHED_INTERNAL_ONLY`
- `FAILED`
- `SKIPPED_EXTERNAL_DISABLED`

Stage 10C service-created events remain `PENDING`; there is no production publisher or connector dispatcher.

## Future Work

Stage 10D should define connector idempotency, worker leasing, retry policy, rollback/compensation contracts, and secret-management boundaries before any external write execution is considered.
