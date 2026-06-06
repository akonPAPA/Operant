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

## Known limitations (future work)

- Real OCR/PDF/Excel text extraction (only an inline-text + stub boundary exists today).
- Real semantic/LLM provider (`FUTURE_SEMANTIC` is a placeholder that fails closed; no LLM call).
- Production queue/outbox + HTTP-backed `AiResultSink` wired to the 07D intake endpoint.
- Deterministic validation/risk engine that consumes the persisted advisory `ExtractionResult`.
