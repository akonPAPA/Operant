# ChangeRequest Contract

Stage: Stage 10C write-intent skeleton.

`ChangeRequest` represents an intended change to an external system. It is not the execution itself.

## Required Meaning

- A ChangeRequest is required before any future ERP, 1C, accounting, warehouse, CRM, or other external write.
- Creating a ChangeRequest does not write externally.
- Validating a ChangeRequest performs deterministic structural checks only.
- Approving a ChangeRequest records human/operator approval but does not execute it.
- Rejecting a ChangeRequest makes it not executable.
- AI, chatbot, frontend, and connector placeholders must not bypass ChangeRequest or write directly to business/master tables.

## Fields

- `id`
- `tenant_id`
- `target_system`
- `target_entity`
- `requested_action`
- `source_type`
- `source_id`
- `request_payload_json`
- `validation_status`
- `approval_status`
- `execution_status`
- `idempotency_key`
- `created_by_user_id`
- `approved_by_user_id`
- `created_at`
- `validated_at`
- `approved_at`
- `rejected_at`
- `executed_at`
- `external_reference`
- `failure_reason`

## Status Values

Validation:

- `PENDING_VALIDATION`
- `VALIDATED`
- `VALIDATION_FAILED`

Approval:

- `NOT_REQUIRED`
- `PENDING_APPROVAL`
- `APPROVED`
- `REJECTED`

Execution:

- `NOT_EXECUTABLE`
- `QUEUED_INTERNAL_ONLY`
- `EXECUTION_DISABLED`
- `EXECUTED`
- `FAILED`

In Stage 10C, external execution remains disabled. New records default to `EXECUTION_DISABLED`, and rejected records become `NOT_EXECUTABLE`. The migration prevents `EXECUTED` rows in this stage.

## Idempotency

`idempotency_key` is tenant-scoped. Reusing the same tenant and key returns the existing ChangeRequest instead of creating a duplicate write intent.

## Audit and Outbox

Creating, validating, approving, rejecting, and marking execution disabled emit audit-compatible events and internal outbox events.
