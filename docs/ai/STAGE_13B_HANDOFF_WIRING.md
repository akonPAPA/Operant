# Stage 13B — Advisory Validation Handoff Wiring + Guarded Operator Trigger

Stage 13B closes the remaining gap from [Stage 13A](STAGE_13A_HANDOFF.md): successful/reviewable
AI-worker advisory results now **automatically** enter deterministic validation from the canonical
intake path, and an operator/admin has a **guarded manual trigger** to (re-)run the handoff.

13A added `AdvisoryExtractionValidationHandoffService` but intentionally did not auto-trigger and added
no endpoint. 13B wires both, reusing the existing services — no parallel subsystem, no second AI-worker
result model, and the 13A service remains the single handoff entry point.

## Canonical path

```
AI worker → POST /api/v1/internal/ai-processing-results  (AI_RESULT_INTAKE)
  → AiWorkerResultIntakeService.intake(): persists advisory ExtractionResult (provider AI_WORKER)
      └─ publishes AdvisoryValidationHandoffRequested (SUCCEEDED / NEEDS_REVIEW only)
  ── intake transaction commits ──
  → AdvisoryValidationHandoffTrigger  @TransactionalEventListener(AFTER_COMMIT, REQUIRES_NEW)
      → AdvisoryExtractionValidationHandoffService.handoff(extractionResultId)   (13A, unchanged)
          → ExtractionValidationService / ValidationRunService (deterministic engine)

Operator: POST /api/v1/validations/advisory-handoff/{extractionResultId}  (VALIDATION_RUN)
  → AdvisoryExtractionValidationHandoffService.handoff(...)   (same 13A service)
```

## Auto-trigger behavior

- The handoff is published as a **transaction-bound event** inside `intake()` and consumed by an
  `@TransactionalEventListener(phase = AFTER_COMMIT)` listener annotated
  `@Transactional(REQUIRES_NEW)`. This guarantees:
  - the handoff runs **only after** the advisory result is durably committed (the deterministic
    validation run reads a committed row);
  - a handoff failure is fully **isolated** — it can never roll back or corrupt the persisted advisory
    result, and is recorded as a bounded `advisory_validation_handoff.auto_trigger_failed` audit event
    rather than propagating.
- The trusted tenant rides on the event and is set explicitly in the listener (never taken from a
  request body), then restored.

### Statuses that trigger vs skip

| Worker status | Auto-trigger? | Behavior |
| --- | --- | --- |
| `SUCCEEDED` | yes | decompose advisory rows → deterministic validation run |
| `NEEDS_REVIEW` | yes | same, when the payload is structurally usable |
| `FAILED` | no | no event published; no decomposition; no run |
| `REJECTED` | no | no event published; no decomposition; no run |

Even if a failed/rejected result were handed off, the 13A service independently fails it closed
(`FAILED_EXTRACTION`), and a nested business-action key fails closed as `UNSAFE_OUTPUT_REJECTED` — no
rows, no run.

## Manual operator endpoint

`POST /api/v1/validations/advisory-handoff/{extractionResultId}`

- Lives on the existing `ValidationController` (`/api/v1/validations`); a non-GET there is auto-guarded
  by `ApiPermission.VALIDATION_RUN` via `ApiPermissionInterceptor` (no interceptor change needed).
- Tenant is resolved server-side from `X-Tenant-Id`; the path carries only the extraction result id —
  **no tenant id is accepted from the request body**. A foreign-tenant result is not found and fails
  closed (`IllegalArgumentException` → bounded `400 BAD_REQUEST` via `GlobalExceptionHandler`).
- Returns the bounded `AdvisoryValidationHandoffResult` only (ids, handoff/validation status, routing,
  issue/approval counts, `advisoryOnly=true`, safe failure reason). It never returns raw advisory
  payload, prompt/source text, secrets, or stack traces.
- Safe to call repeatedly — the 13A handoff is idempotent (returns `duplicate=true`, same run).

## Audit & observability

- `ai_processing_result.intake_succeeded` / `intake_duplicate` / `intake_rejected` — existing intake.
- `advisory_validation_handoff.accepted | failed_closed | unsafe_rejected | duplicate | rejected` —
  emitted by the 13A service (auto and manual).
- `advisory_validation_handoff.auto_trigger_failed` — bounded reason token only, if the after-commit
  handoff throws unexpectedly. No payloads, document bodies, or secrets are ever logged.

## Safety boundaries (unchanged)

AI output is advisory only. The handoff creates only advisory `ExtractedField`/`ExtractedLineItem`
rows (+ bounded `SourceEvidence`) and deterministic validation artifacts (`ValidationRun`,
`ValidationIssue`, `ApprovalRequirement`). It never creates or mutates a quote, order, draft order,
customer, inventory, price, discount, margin rule, connector, or ERP/1C state, and carries no
executable/business-action surface. Tenant isolation is enforced on every path.

## Idempotency

- Intake dedupes to one advisory run per processing job; a duplicate intake returns the existing record
  and does **not** re-publish the trigger event.
- The handoff dedupes on "a validation run already exists for this extraction result": a duplicate
  auto-trigger or manual endpoint call returns `duplicate=true` with the existing run and creates no
  duplicate rows/runs.

## Known limitations

- The auto-trigger runs **synchronously after commit** on the request thread (no async/job layer exists
  for this path). For very large advisory payloads this adds latency to the intake response path; a
  future async/queue worker can move it off-thread without changing the contract.
- Idempotency uses the existing run history as the dedupe key (no new unique constraint); concurrent
  duplicate triggers for the same result are not separately locked.
- An unexpected handoff exception in the after-commit listener rolls back only the handoff's own work
  (fail-closed) and is audited; it does not roll back the committed advisory result.

## Targeted test commands (from `apps/core-api`)

```
mvn -o -Dtest=AdvisoryExtractionValidationHandoffServiceStage13ATest test
mvn -o -Dtest=AiWorkerResultIntakeServiceTest test
mvn -o -Dtest=AiWorkerResultHandoffWiringStage13BTest test
mvn -o -Dtest=ValidationRunServiceStage5Test,ValidationReviewBridgeStage5BTest,AiWorkerResultIntakeServiceTest,AiWorkerResultHandoffWiringStage13BTest,ApiPermissionInterceptorPermissionTest test
mvn -o compile
```
