# Stage 39B AI Result Handoff Contract

Status: hardening.

## Boundary

AI worker output is untrusted advisory extraction data. Core API remains the authority for tenant,
source correlation, job state, approval, execution, connector access, pricing, inventory, margin,
customer, product, quote, order, audit, idempotency, and external writes.

Allowed AI result data:

- bounded extraction fields, line-item hints, evidence, warnings, confidence, and safe failure tokens;
- worker provenance such as provider name/version and supported pipeline mode;
- correlation fields that must match the Core-owned processing job.

Rejected AI result data:

- missing or unknown `jobId`;
- missing or mismatched `tenantRef`;
- missing or mismatched `sourceId` / `sourceType`;
- missing or unsupported pipeline mode;
- malformed advisory extraction schema;
- oversized payload/list entries;
- authority keys such as `tenantId`, `actorId`, `permissions`, `status`, `approval`, `execution`;
- connector/ERP/1C/write/tool-call commands;
- duplicate terminal results that differ from the first accepted result.

## Replay Rule

Core persists one `AI_WORKER` advisory extraction run per processing job. The first accepted result stores
a deterministic intake fingerprint over the terminal status, schema, pipeline, safe failure reason,
warnings/errors/signals, provider metadata, and advisory extraction payload.

Same fingerprint replay is idempotent and creates no second run/result/business effect. A different
fingerprint for the same job is rejected as `conflicting_terminal_result`.

## Business Effects

Result intake may persist only:

- `ExtractionRun`;
- `ExtractionResult`;
- the processing job's own terminal status;
- bounded audit metadata;
- the advisory validation handoff event for structurally usable advisory results.

It must not create or mutate quote, order, customer, product, price, inventory, approval, execution, or
connector/ERP/1C state.

## Tests

Targeted worker:

```bash
cd apps/ai-worker
python -m pytest tests/jobs/test_ai_result_handoff.py tests/jobs/test_ai_job_security.py
```

Targeted Core API:

```bash
cd apps/core-api
./mvnw test -Dtest=AiWorkerResultIntakeServiceTest,AiWorkerResultHandoffWiringStage13BTest,WorkerRuntimeLifecycleStage29Test
```
