# OP-CAP-14C — Operator Correction / Approval Command Layer

The first safe backend command surface for operator actions from the validation review workspace
(OP-CAP-14A/14B). It lets an operator correct an advisory extracted field/line item, resolve a
validation issue, and raise an approval request — always through the tenant-scoped, permissioned,
audited Core command service. It adds **no** external/ERP/1C/connector write and **no** final
quote/order creation.

## Endpoints

All under the canonical `ValidationController` (`/api/v1/validations`), keyed on the validation run:

| Method & path | Purpose |
| --- | --- |
| `POST /api/v1/validations/{validationRunId}/review/corrections` | Correct an advisory field or line item |
| `POST /api/v1/validations/{validationRunId}/review/issues/{issueId}/resolution` | Resolve / ignore / escalate a validation issue |
| `POST /api/v1/validations/{validationRunId}/review/approval-requests` | Raise a minimal pending approval request |

Service: `ValidationReviewCommandService`. Request/response DTOs: `ValidationReviewCommandDtos`.

### Correction request (`ValidationReviewCorrectionRequest`)
`targetType` (`FIELD` | `LINE_ITEM`, strict allowlist), `targetId`, `correctedValue` (FIELD),
`correctedQuantity` + `correctedUom` (LINE_ITEM), `reason`, optional `actorUserId`, optional
`clientRequestId`. The correction must target a field/line that belongs to the run's extraction result;
any other target type (source evidence, extraction, audit, …) is rejected with
`unsupported_correction_target`.

### Issue resolution request (`ValidationIssueResolutionRequest`)
`resolution` (`RESOLVED` | `IGNORED` | `ESCALATED`), `reason` (required), optional `correctionActionId`,
optional `actorUserId`, optional `clientRequestId`. Legal source states are `OPEN`/`ACKNOWLEDGED`;
re-applying the same resolution is an idempotent no-op; moving an already-decided issue to a different
state is rejected with `illegal_issue_transition`.

### Response (`ValidationReviewActionResult`)
`actionId`, `validationRunId`, `targetType`, `targetId`, `actionType`, `actionStatus`,
`approvalRequired`, `approvalRequestId`, `resolvedIssueId`, `issueResolution`, `createdBy`,
`createdAt`, `clientRequestId`, bounded `message`. No raw payload.

## Command boundary

- Corrections mutate **advisory extraction rows only** — `extracted_field.normalized_value` (raw value
  provenance preserved) and `extracted_line_item` normalized quantity/UOM via the existing
  `correctQuantity` / `correctUom` domain methods. They never touch product, customer, inventory,
  price, discount or margin master data.
- Issue resolution updates `validation_issue.status` only.
- Approval requests reuse the existing `ApprovalRequirementService` (creates a pending requirement);
  no new approval workflow engine is introduced.
- No final quote/order is created; no ERP/1C/accounting/warehouse/connector write occurs.

## Data model

No new table or migration. Each accepted command is recorded as a tenant-owned `OperatorAction`
(the existing operator-action table) via `OperatorActionService.record(...)`, which also writes the
paired `AuditEvent`. Bounded before/after snapshots, the reason, the validation run id and an optional
`clientRequestId` are stored in the action's `metadata_json` (size-bounded; no raw advisory payload,
document body, prompt, secret, token or stack trace). The narrow `ExtractedField.applyOperatorCorrection`
method sets the existing `normalized_value` column — no schema change.

## Permission model

`ApiPermissionInterceptor` adds a precise rule: **non-GET under `/api/v1/validations/{id}/review`
requires `REVIEW_ACTION`**, checked before the generic `/api/v1/validations` non-GET → `VALIDATION_RUN`
rule. This reuses the existing review-write permission and cleanly separates an operator review action
from triggering the validation engine (`VALIDATION_RUN`, e.g. the advisory handoff) and from reading
the review (`VALIDATION_READ`). `VALIDATION_RUN` alone is **not** sufficient for a review command, and
`REVIEW_ACTION` alone is **not** sufficient for the engine trigger — both are asserted by tests.

## Audit behavior

Every accepted correction/resolution/approval-request emits an `AuditEvent` (via `OperatorActionService`)
including tenant id, action type, target type/id, actor, bounded reason and a bounded before/after
summary. The idempotent no-op resolution path records no duplicate action/audit. Forbidden content
(raw document body, raw AI payload, prompt text, secrets/tokens, stack traces) is never logged.

## Current limitations

- Field correction updates the advisory `normalized_value`; it does not re-run the deterministic
  validation engine. Re-validation remains a separate explicit action (`VALIDATION_RUN`).
- Idempotency is implemented at the state level for issue resolution (re-applying the same resolution
  is a no-op). Corrections are append-only operator actions (matching the existing comparable-command
  convention); `clientRequestId` is stored/echoed for correlation but does not deduplicate corrections.
- The approval request is a minimal pending `ApprovalRequirement`; the full approve/reject decision
  flow continues to live in the existing approval/review-case infrastructure.
- Frontend remains read-only (OP-CAP-14B); live action buttons are intentionally **not** wired in this
  task.

## Explicit safety note

No ERP/1C/accounting/warehouse/connector external write, no final quote/order creation, and no
product/customer/inventory/price master-data mutation path is added by this stage. Tests assert those
counts are unchanged across a correction + resolution.

## Next intended stage

OP-CAP-14D — wire the operator review workspace UI to these commands behind the permissioned backend.
