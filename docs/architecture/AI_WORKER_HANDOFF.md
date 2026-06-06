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

## Core API receiving side

Core API already owns a mature extraction/processing-job subsystem
(`ProcessingExtractionController`, `ExtractionPipelineService`, `InboundDocumentService`, …). To avoid
duplicating those domain concepts, OP-CAP-07C defines the worker-side contract and `AiResultSink` port
rather than adding a parallel receiving endpoint. A future slice can implement an `AiResultSink` that
posts `AiProcessingJobResult` to an existing Core API extraction-result intake.

## Known limitations (future work)

- Real OCR/PDF/Excel text extraction (only an inline-text + stub boundary exists today).
- Real semantic/LLM provider (`FUTURE_SEMANTIC` is a placeholder that fails closed; no LLM call).
- Production queue/outbox + HTTP-backed `AiResultSink`.
- Persisted Core API intake endpoint for `AiProcessingJobResult`.
