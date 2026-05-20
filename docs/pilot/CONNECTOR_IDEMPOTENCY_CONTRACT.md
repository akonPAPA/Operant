# Connector Idempotency Contract

Stage 10F introduces `ConnectorCommand` as an internal-only execution intent. It is not an executor.

## Persistent Concept

Table: `connector_command`

Fields:

- `id`
- `tenant_id`
- `change_request_id`
- `outbox_event_id`
- `connector_type`
- `operation_type`
- `idempotency_key`
- `payload_json`
- `status`
- `attempt_count`
- `max_attempts`
- `next_attempt_at`
- `last_error`
- `dead_letter_reason`
- `retryable`
- `created_at`
- `updated_at`

## Rules

- Commands are tenant-scoped.
- Idempotency keys are derived from tenant, connector type, operation type, and source id.
- Duplicate idempotency keys return the existing command.
- Commands can be created only from approved ChangeRequests.
- Commands remain `EXECUTION_DISABLED` or `SKIPPED_EXTERNAL_DISABLED` for anything that would call an external system.
- Commands do not require connector credentials.
- Commands do not write ERP, 1C, accounting, warehouse, CRM, payment, or external systems.
