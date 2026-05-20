# Stage 10C - ChangeRequest and Transactional Outbox Skeleton

Stage 10C adds the first safe write-intent infrastructure for future controlled ERP, 1C, accounting, warehouse, and CRM integrations.

This stage records intended external changes only. It does not execute external writes, does not call connector workers, does not introduce real AI provider access, and does not require provider keys.

## Backend Scope

- `change_request` records tenant-scoped write intent.
- `outbox_event` records internal-only domain events about ChangeRequest lifecycle changes.
- `POST /api/v1/change-requests` creates write intent.
- `POST /api/v1/change-requests/{id}/validate` runs deterministic structural validation.
- `POST /api/v1/change-requests/{id}/approve` records approval but keeps execution disabled.
- `POST /api/v1/change-requests/{id}/reject` records rejection and makes the request not executable.
- `GET /api/v1/change-requests` and `GET /api/v1/outbox-events` expose tenant-scoped records for local/demo inspection.

## Safety Decisions

- Stage 10C records write intent only.
- Connector execution remains disabled.
- No connector dispatcher calls real systems.
- Approval does not execute a ChangeRequest.
- Execution status remains `EXECUTION_DISABLED` by default, or `NOT_EXECUTABLE` after rejection.
- Outbox events remain internal-only and start as `PENDING`.
- AI remains advisory only.
- Stage 10B shadow-mode and pilot metrics behavior remains unchanged.

## Demo Flow

1. Create a ChangeRequest with a draft quote/order-like or generic JSON object payload.
2. Validate it.
3. Approve it.
4. Confirm execution remains `EXECUTION_DISABLED`.
5. Confirm outbox events exist for creation, validation, approval, and external-execution-disabled.
6. Confirm no external connector write happens.

## Remaining Blockers

- Connector idempotency implementation.
- Connector execution worker.
- Rollback and compensation contracts.
- Production auth/RBAC proof.
- Real-provider safety and secret-management harness.
- Production pilot dashboard UI.
- Normal Python/pytest tooling if it remains unavailable for AI-worker tests.

## Stage 10D Recommendation

Implement connector idempotency and execution-readiness contracts while still keeping real connector execution disabled until RBAC, rollback, secret-management, and design-partner acceptance are proven.
