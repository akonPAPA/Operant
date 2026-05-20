# Connector Worker Disabled Contract

Stage 10F includes a worker-readiness skeleton only.

## Contract

`ConnectorWorkerReadinessService` can scan internal connector commands and mark eligible commands as `SKIPPED_EXTERNAL_DISABLED`.

It must not:

- Call external systems.
- Require connector credentials.
- Dispatch ERP, 1C, accounting, warehouse, CRM, payment, or external API writes.
- Convert connector commands into executed records.

## Retry and Dead Letter

Retry and dead-letter fields are internal-only:

- `attempt_count`
- `max_attempts`
- `next_attempt_at`
- `last_error`
- `dead_letter_reason`
- `retryable`

These fields prepare future worker behavior but do not enable execution in Stage 10F.
