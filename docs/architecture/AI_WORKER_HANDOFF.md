# AI Worker Job Integration + Secure Core Handoff (OP-CAP-07C)

This documents the foundation that lets the rest of OrderPilot *use* the advisory AI worker without
letting it become a business actor. It wraps the OP-CAP-07B understanding pipeline (the extraction
engine) with a job/handoff/security shell.

```text
Core API / channel / document
  -> AiProcessingJobRequest (scoped, no secrets, no DB creds)
  -> job-envelope security validation (fail closed)
  -> 07B extraction pipeline (deterministic, advisory only)
  -> AiProcessingJobResult (schema-valid, controlled status)
  -> AiResultSink handoff back to Core API
  -> Core API stores/consumes the advisory result; deterministic validation happens later
```

`07B = extraction engine. 07C = job/handoff/security orchestration around it.`

## Components (apps/ai-worker)

- `orderpilot_ai_worker/jobs/models.py` — `AiProcessingJobRequest`, `AiProcessingJobResult`,
  `AiJobSourceType`, `AiJobStatus`, `ProviderMode`, `ProviderMetadata`, `JobSecurityContext`.
- `orderpilot_ai_worker/jobs/security.py` — fail-closed input controls + optional stdlib-HMAC signing
  helpers (`validate_job_envelope`, `compute_request_signature`).
- `orderpilot_ai_worker/jobs/handler.py` — `process_ai_extraction_job(request) -> result`.
- `orderpilot_ai_worker/jobs/handoff.py` — `AiResultSink` port + `InMemoryResultSink` +
  `assert_handoff_safe`.

## Job statuses

| Status         | Meaning                                                                    |
| -------------- | -------------------------------------------------------------------------- |
| `SUCCEEDED`    | schema-valid advisory extraction exists and is confident enough            |
| `NEEDS_REVIEW` | extraction exists but low confidence or prompt-injection signals present   |
| `FAILED`       | worker could not produce a valid result (provider error / invalid output)  |
| `REJECTED`     | input malformed / unsafe / too large / unsupported per worker-local checks |

## Security boundary

- **Input:** required `job_id` / `source_id` / `tenant_ref`; bounded raw text (`MAX_RAW_TEXT_CHARS`)
  and metadata (`MAX_METADATA_BYTES`); allow-listed source types and pipelines; **no external URL /
  object-storage fetch** (reference-only jobs are `REJECTED`); empty input fails closed.
- **Output:** advisory `ExtractionResult` only; no action/command/mutation field; bounded error
  reasons (no stack traces, no raw injection payload, no secrets); prompt-injection signals kept in a
  separate quarantine field; confidence/risk preserved.
- **Transport (future):** HTTPS/TLS + service token verified by Core API; optional standard HMAC
  signed requests and timestamp+nonce replay resistance. No custom crypto, no secrets in repo.
- **Logging:** one bounded structured line per job (`job_id`, `source_type`, `source_id`, `status`,
  `duration_ms`, `warning_count`, `prompt_injection_signal_count`); never raw content or secrets.

## Tenant authority

`tenant_ref` is carried for scoping/correlation only. **Core API remains the tenant authority** and
re-establishes tenant scope (and re-validates) when it consumes the advisory result. The worker has
no business/master DB or ERP/connector client and no mutation path.

## Core API receiving side (OP-CAP-07D)

OP-CAP-07D adds the Core API receiving layer that persists AI-worker output as **advisory** data and
moves the processing job status — without touching any business entity. It reuses the existing
extraction/processing-job subsystem (`ProcessingJob`, `ExtractionRun`, `ExtractionResult`,
`AuditEventService`) instead of inventing parallel concepts.

### Endpoint

```text
POST /api/v1/internal/ai-processing-results
```

Internal/service-facing (not a public customer UI). Tenant is resolved server-side from
`TenantContext` (`X-Tenant-Id`). Guarded by `ApiPermissionInterceptor` requiring the
`AI_RESULT_INTAKE` permission (existing `X-OrderPilot-Permissions` boundary — no secrets hardcoded).
Thin controller `AiWorkerResultIntakeController` → `AiWorkerResultIntakeService`.

### Request contract

`AiResultIntakeDtos.AiProcessingResultIntakeRequest`: `jobId`, `tenantRef` (correlation only, NOT
authority), `sourceType`, `sourceId`, `status`, `extractionResult` (advisory map), `warnings`,
`errors`, `promptInjectionSignals`, `providerMetadata`, `schemaVersion`, `startedAt`, `completedAt`,
`durationMs`, `safeFailureReason`.

### Trust boundary & fail-closed validation

AI output is **untrusted advisory data**. The intake rejects (HTTP 400, safe reason token) when:
`jobId`/`sourceId` missing; `sourceType`/`status`/`schemaVersion` unsupported; `tenantRef` disagrees
with the trusted tenant; the `jobId` is not an existing tenant-scoped `ProcessingJob`; `sourceType` /
`sourceId` do not match the job's `targetType` / `targetId`; warnings/errors/signals exceed bounds;
the advisory payload exceeds `MAX_PAYLOAD_CHARS`; or the `extractionResult`/`providerMetadata` carries
a forbidden top-level action key (`action`, `command`, `approve`, `execute`, `write`, `mutation`,
`sql`, `erpWrite`, `inventoryUpdate`, `priceUpdate`, `customerUpdate`, `orderCreate`, `quoteApprove`).

### Persistence & status mapping

The advisory result is persisted as an `ExtractionRun` (provider type `AI_WORKER`) + `ExtractionResult`
whose `result_json` is marked `advisoryOnly` / `untrustedUntilValidation`, linked to the processing job
and source. Status mapping:

| Worker status  | ProcessingJob | ExtractionRun | ExtractionResult.validationStatus |
| -------------- | ------------- | ------------- | --------------------------------- |
| `SUCCEEDED`    | `SUCCEEDED`   | `SUCCEEDED`   | `READY_FOR_VALIDATION`            |
| `NEEDS_REVIEW` | `NEEDS_REVIEW`| `NEEDS_REVIEW`| `NEEDS_REVIEW`                    |
| `FAILED`       | `FAILED`      | `FAILED`      | `FAILED`                          |
| `REJECTED`     | `REJECTED`    | `FAILED`*     | `REJECTED`                        |

\* the extraction-run state machine has no REJECTED; the precise distinction is preserved on the
result's `validationStatus`. No DB migration is required (`REJECTED` is a String status value).

### Idempotency

At most one `AI_WORKER` `ExtractionRun` is persisted per processing job
(`findFirstByTenantIdAndProcessingJobIdAndProviderType`). A repeat delivery is a no-op that returns the
already-persisted record (`duplicate=true`); an older/conflicting result never overwrites it.

### Audit

`AuditEventService` records `ai_processing_result.intake_succeeded` / `intake_duplicate` /
`intake_rejected` with bounded metadata only (jobId, sourceType, sourceId, resultStatus, schemaVersion,
providerName, warning/error/promptInjectionSignal counts, durationMs). Never raw document/message text,
the extraction payload, secrets, or service tokens.

### Explicit non-goals (07D)

- No quote/order creation, no validation/risk engine, no substitution/pricing/inventory/customer
  mutation, no ERP/1C/connector write — only advisory result persistence + job status.
- Not a production queue/outbox; delivery is a direct internal endpoint call.
- No HTTP `AiResultSink` on the worker side yet (the worker still publishes via its in-memory port).

## Deterministic validation & risk routing (OP-CAP-07E)

OP-CAP-07E consumes the persisted advisory `ExtractionResult` (07D) and produces deterministic
validation issues, a risk level and a routing decision — the bridge between untrusted AI output and a
future operator/draft workflow. It **reuses** the OP-CAP-08A `ValidationEngineService` (stateless,
read-only) for the heavy customer/product/quantity/UOM/inventory/price resolution, then layers
AI-advisory gating on top.

```text
advisory ExtractionResult (07D, untrusted)
  -> ExtractionAdvisoryValidationService.validate(extractionResultId)
  -> AI gating (provider-failure / rejected / prompt-injection / missing-intent / missing-line-items)
     + reused ValidationEngineService deterministic checks
  -> deterministic issues + risk level + routing decision
  -> persisted ai_extraction_validation (+ issues) ; processing job status updated
```

### Endpoints

- `POST /api/v1/internal/extractions/{extractionResultId}/validate` — internal trigger, guarded by
  `VALIDATION_RUN`.
- `GET /api/v1/extractions/{extractionResultId}/validation` — read view, guarded by `EXTRACTION_READ`.

Tenant is resolved server-side from `TenantContext`; the result must belong to that tenant or the call
fails closed. The advisory wrapper must be `advisoryOnly` + `source=AI_WORKER`.

### Issue taxonomy (`AiValidationIssueCode`)

`MISSING_INTENT`, `MISSING_LINE_ITEMS`, `UNKNOWN_CUSTOMER`, `UNKNOWN_PRODUCT`, `INVALID_QUANTITY`,
`INVALID_UOM`, `LOW_CONFIDENCE_FIELD`, `PROMPT_INJECTION_SIGNAL`, `UNSUPPORTED_SOURCE_TYPE`,
`PROVIDER_FAILURE`, `EXTRACTION_REJECTED`, `INVENTORY_UNKNOWN`, `PRICE_UNKNOWN`. Severity reuses
`ValidationSeverity` (INFO/WARNING/ERROR/CRITICAL). Issue messages are bounded — never raw message/
document text.

### Risk & routing

| Risk (`AiValidationRiskLevel`) | When |
| ------------------------------ | ---- |
| `BLOCKED`  | any CRITICAL/ERROR issue, provider failure, rejected extraction, missing line items |
| `HIGH`     | prompt-injection signals, unsupported source type, or ≥2 unknown products |
| `MEDIUM`   | any WARNING (unknown customer/product, low confidence, invalid UOM, inventory/price unknown) |
| `LOW`      | no issues, known customer/product, positive quantity, valid UOM |

| Routing (`AiValidationRoutingDecision`) | When |
| --------------------------------------- | ---- |
| `FAILED_VALIDATION`          | worker status FAILED |
| `BLOCKED_INVALID_EXTRACTION` | any other BLOCKED (rejected / critical issue / missing line items) |
| `NEEDS_HUMAN_REVIEW`         | risk HIGH or MEDIUM |
| `READY_FOR_DRAFT_REVIEW`     | risk LOW (NOT a quote/order — only a future draft candidate) |

Prompt injection and low confidence can never reach `READY_FOR_DRAFT_REVIEW`. Provider-failed /
rejected results never enter business validation as valid.

### Persistence, status, idempotency, audit

- Persisted in narrow new tables `ai_extraction_validation` (one row per tenant+extractionResultId,
  unique) + `ai_extraction_validation_issue` (Flyway `V39`). No quote/order tables; no master-data
  mutation.
- Processing job status: READY→`SUCCEEDED`, NEEDS_HUMAN_REVIEW→`NEEDS_REVIEW`,
  BLOCKED_INVALID_EXTRACTION→`REJECTED`, FAILED_VALIDATION→`FAILED` (reuses 07D `ProcessingJob` mark
  methods).
- Idempotent: re-validation replaces the existing issues and recomputes the header in place — no
  duplicate issue rows, no conflicting routing.
- Audit `ai_extraction_validation.completed` with bounded metadata (risk, routing, counts, source
  correlation) — never raw text, payload, or secrets.

### Explicit non-goals (07E)

- No quote/order draft creation, no final business mutation, no connector/ERP/1C write, no analytics,
  no frontend. AI output stays advisory/untrusted; this layer only produces a safe routing decision.

## Known limitations (future work)

- Real OCR/PDF/Excel text extraction (only an inline-text + stub boundary exists today).
- Real semantic/LLM provider (`FUTURE_SEMANTIC` is a placeholder that fails closed; no LLM call).
- Production queue/outbox + HTTP-backed `AiResultSink` wired to the 07D intake endpoint.
- Draft quote/order workflow that consumes a `READY_FOR_DRAFT_REVIEW` routing decision (07E stops at
  the routing decision; it never creates a draft).
