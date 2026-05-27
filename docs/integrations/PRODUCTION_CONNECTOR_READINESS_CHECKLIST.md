# Production Connector Readiness Checklist

Production connector activation is out of scope for Stage 9B.

Before enabling any real ERP/1C write path:

- Security acceptance for credential custody is complete.
- Secrets are stored in an approved secrets manager.
- No plaintext secret values are stored in the database or logs.
- ChangeRequest approval is mandatory for every external write.
- Idempotency keys are stable and verified against duplicate execution.
- Retry policy defines max attempts, retryable failure classes, and manual override.
- Failure runbook is tested.
- Connector audit events are retained and tenant-scoped.
- External network allowlists and rate limits are documented.
- Compensation and rollback plan is approved.

Until this checklist is accepted, OrderPilot remains demo/local-only for connector execution.
