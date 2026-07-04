# AI Work Schema V1

AI Work responses are typed, schema-versioned, operator-safe advisory projections. Provider output
is untrusted internal input. The public API never returns the provider payload, generated-text field,
prompt, provider strategy/version, or internal authority metadata.

## Schema identifiers

| Schema identifier | Backend work type | Purpose |
| --- | --- | --- |
| `AI_WORK_SCHEMA_V1_REQUEST_SUMMARY` | `REQUEST_SUMMARY` | Summarize a backend-resolved request context. |
| `AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION` | `NEXT_ACTION_SUGGESTION` | Present bounded candidate actions for operator review. |
| `AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT` | `CUSTOMER_REPLY_DRAFT` | Present a draft reply that is never sent by AI Work. |
| `AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION` | `VALIDATION_EXPLANATION` | Explain existing backend validation results. |

The legacy internal `SOURCE_CONTEXT_DIGEST` work type uses
`AI_WORK_SCHEMA_V1_REQUEST_SUMMARY`; it does not create a fifth public schema.

## Public response allowlist

`AiWorkSuggestionResponse` exposes:

- the existing opaque suggestion handle plus `workType`, `sourceType`, and `status`;
- `schemaVersion`;
- backend-owned `riskLevel` and bounded `confidence`;
- bounded `summary`;
- typed `displayFields` (`key`, `label`, `value`, `kind`, optional `confidence` and
  `evidenceRef`);
- typed `evidence` (`sourceType`, `sourceLabel`, optional bounded `excerpt` and `confidence`);
- typed `riskFlags` (`code`, `severity`, `message`);
- typed `nextActionCandidates` (`actionType`, `label`, optional `description`,
  `requiresHumanApproval`, optional `disabledReason`);
- `safety` (`advisoryOnly`, `externalExecution`, `connectorCall`, `outbox`,
  `humanApprovalRequired`);
- existing public lifecycle timestamps and decision reason.

The backend forces action candidates to `requiresHumanApproval=true`; a provider cannot waive this
boundary. V1 safety values are `advisoryOnly=true`, `externalExecution=DISABLED`,
`connectorCall=NOT_INVOKED`, and `outbox=NOT_REQUESTED`.

## Forbidden public data

Responses must not contain tenant or actor identifiers, idempotency keys, audit identifiers,
internal source/storage identifiers, connector internals or credentials, provider strategy/model
metadata, raw payloads, provider JSON, generated-text fields, prompts or hidden instructions,
tokens, API keys, passwords, secret references, stack traces, exception classes, or raw backend
errors.

Unknown provider fields are ignored. Known text fields are bounded and rejected when they contain
obvious credential, token, prompt, internal-authority, raw-payload, storage-key, or stack-trace
markers.

## Validation and fail-closed behavior

The backend derives the expected schema from the backend-owned work type, requires the exact schema
identifier in the internal structured payload, and validates only the fields defined for that
schema. A missing, mismatched, malformed, or structurally invalid payload returns:

`AI suggestion could not be safely rendered.`

The failure projection contains no display fields, evidence, candidate actions, or raw fallback.
Malformed evidence is omitted. Raw provider output is never substituted for a failed projection.

## API access and authority

- `GET /api/v1/ai-work/**` requires tenant-user `REVIEW_READ`.
- `POST /api/v1/ai-work/**` requires tenant-user `AI_WORK_ACTION`.
- The RFQ contextual route resolves the tenant, actor, source handle, source type, source context,
  status eligibility, risk, and safety state on the backend.
- The client sends only the selected advisory work intent on contextual routes. Idempotency remains
  an `Idempotency-Key` header where supported.
- External customers have no direct AI Work API access.
- AI worker/service-account intake uses its separate `AI_RESULT_INTAKE` boundary and does not inherit
  tenant-operator AI Work permissions.
- Operant support and maintenance access is a separate support access plane. Support permission does
  not grant tenant `REVIEW_READ` or `AI_WORK_ACTION`.

## Frontend rendering

The dashboard switches on `schemaVersion` and renders typed fields only. It does not render JSON
dumps or technical error bodies. RFQ handoff suggestions show `ADVISORY ONLY`, backend risk,
evidence when present, candidate actions, human-approval markers, and the disabled
external/connector/outbox safety state.

## Business boundary

AI suggests. Rules validate. A human approves risky business action. Backend command services write.
Audit records the controlled workflow. AI Work creation or acceptance does not create or approve a
quote, order, customer, product, inventory, price, connector command, `change_request`, or external
outbox event. External execution remains available only through an explicit gated backend workflow.

## Migration

Before V1, the public mapper inferred display fields heuristically from unversioned internal JSON.
V1 requires an exact schema identifier and schema-specific validator. Existing persisted rows
without a valid V1 internal schema marker fail closed; no compatibility path emits their raw text or
payload.
