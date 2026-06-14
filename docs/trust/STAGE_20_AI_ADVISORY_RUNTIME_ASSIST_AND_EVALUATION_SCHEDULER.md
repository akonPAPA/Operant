# OP-CAP-20 — AI Advisory Runtime Assist + Bounded Evaluation Scheduler

## 1. Purpose

OP-CAP-20 makes the OP-CAP-19 advisory memory retrieval + evaluation infrastructure useful in a real
runtime-assist workflow, while preserving the core safety invariant unchanged:

```
AI / memory / advisory hints may suggest.
Deterministic backend services decide.
No advisory hint may mutate business state.
No AI/memory output may approve, override, export, resolve, or write trusted business data.
```

It adds two backend-only layers:

- **Layer A — Runtime Assist Surface:** a read-only service/API that turns OP-CAP-19 advisory retrieval
  into ranked, explainable, bounded hints for a concrete workflow context.
- **Layer B — Bounded Evaluation Batch Runner Foundation:** a manual-trigger service/API that runs a
  bounded, tenant-scoped evaluation run by reusing the OP-CAP-19 evaluation entities/services.

## 2. Architecture

### Layer A — `AiAdvisoryRuntimeAssistService`
- Accepts a tenant-scoped `AssistCommand(tenantId, contextType, contextId, taskType?, lookupKey?, maxHints?)`.
- Resolves a concrete context via `RuntimeAssistContextType`. The first context is
  `TRUST_VALIDATION_REVIEW`, which maps deterministically to task type `TRUST_SIGNAL_EXPLANATION`
  (namespaces `TRUST_SIGNAL_HINT`, `VALIDATION_EXPLANATION`).
- Delegates to OP-CAP-19 `AiAdvisoryMemoryRetrievalService.retrieve(...)` with `namespaces = null` so the
  task type drives its **bounded** relevant namespaces (never a tenant-wide scan).
- Is a **pure transform** of the OP-CAP-19 retrieval output: it preserves the existing deterministic
  ranking exactly (`rank` = retrieval order) and only adds deterministic, explainable presentation
  (`title`, `evidenceSummary`, `sourceAuthority`, `applicability`, `safetyLevel`). It does **not** re-read
  memory records or any business state.

### Layer B — `AiMemoryEvaluationBatchRunnerService`
- Accepts `BatchRunCommand(runType, caseSource, maxCases?, maxResultsPerCase?, dryRun, manualCases)`.
- For `MANUAL_CASES`: creates a run, adds clamped cases, and (unless `dryRun`) executes it — all via the
  existing OP-CAP-19 `AiMemoryEvaluationService` (`createEvaluationRun` / `addCase` / `runEvaluation`),
  persisting through the existing `ai_memory_evaluation_run/case/result` tables.
- Refuses `RECENT_CORRECTIONS` / `RECENT_TRUST_EVENTS` (scaffold only — no obviously-safe bounded query
  source yet) rather than introducing an unbounded scan.
- There is **no always-on scheduler** in this repository (`@Scheduled`/`@EnableScheduling` are absent), so
  this is a manual-trigger foundation only; it never starts heavy work automatically.

## 3. Endpoints

| Method | Route | Purpose |
| ------ | ----- | ------- |
| GET  | `/api/v1/trust/ai-memory/advisory-assist` | Layer A — ranked advisory hints for a context (query params: `contextType`, `contextId?`, `taskType?`, `lookupKey?`, `maxHints?`) |
| POST | `/api/v1/trust/ai-memory/evaluations/batch-runs` | Layer B — create + (optionally) execute a bounded evaluation run |
| GET  | `/api/v1/trust/ai-memory/evaluations/runs/{runId}` | Existing OP-CAP-19 read — run status (reused, not duplicated) |
| GET  | `/api/v1/trust/ai-memory/evaluations/runs/{runId}/results` | Existing OP-CAP-19 read — run results (reused, not duplicated) |

## 4. Permissions

- **Layer A** reuses the existing read permission `TRUST_AI_MEMORY_READ` (the assist GET lives under
  `/api/v1/trust/ai-memory`, mapping to the generic AI-memory read rule). No new permission was added —
  a narrower one was not justified.
- **Layer B** batch run **executes** an evaluation, so it requires the strongest existing
  `TRUST_AI_MEMORY_EVALUATION_RUN` (a dedicated interceptor rule placed before the generic evaluations
  non-GET → `WRITE` rule). Generic `TRUST_AI_MEMORY_WRITE` and the weaker
  `TRUST_AI_MEMORY_EVALUATION_WRITE` are insufficient.
- Reads of runs/results continue to require `TRUST_AI_MEMORY_EVALUATION_READ`.

## 5. Safety model

**No business mutation occurs from runtime assist.**
**No AI/memory hint approves, exports, overrides, resolves, or writes trusted business data.**

- Tenant isolation is mandatory: tenant id is resolved from `TenantContext`, never from the request body,
  and every underlying OP-CAP-19 query is tenant-scoped.
- Only `ACTIVE` (non-superseded, non-invalidated, non-expired) memory is ever served (enforced by the
  OP-CAP-19 retrieval query).
- Responses expose only sanitized, length-bounded metadata/summaries — never raw documents, prompts,
  messages, or normalized values. `contextId` is an advisory correlation reference only; it is never used
  to load business state.
- Every hint carries `advisoryOnly = true` and `safetyLevel = ADVISORY_ONLY`, and every response restates
  `deterministicValidationRequired`.

## 6. Why runtime assist is advisory only

Runtime assist never produces a decision, a write, or an approval. It re-presents already-governed,
low-authority, tenant-scoped memory that OP-CAP-17F created and OP-CAP-19 ranked. The deterministic
validation engine, deterministic command services, and human approval gates remain the sole authorities
for orders, quotes, inventory, prices, payments, counterparty trust, and approval status.

## 7. Bounds / clamps

| Bound | Value |
| ----- | ----- |
| Runtime assist `maxHints` | default 5, clamped ≤ 15 (then OP-CAP-19 clamps ≤ 25) |
| Assist `summary` | ≤ 280 chars |
| Assist `title` | ≤ 160 chars |
| Batch `maxCases` | default 50, clamped ≤ 200 (mirrors `AiMemoryEvaluationService.MAX_CASES_PER_RUN`) |
| Batch `maxResultsPerCase` | default 10, clamped ≤ 20 (then OP-CAP-19 clamps ≤ 25) |
| Evaluation failure reason | ≤ 280 chars (OP-CAP-19) |

## 8. Evaluation runner behavior

1. Validates `runType` and `caseSource`; non-`MANUAL_CASES` sources are rejected with `ConflictException`.
2. Clamps `maxCases` and `maxResultsPerCase`; requires ≥ 1 manual case.
3. Concurrency/idempotency guard: refuses to start if an active (`PENDING`/`RUNNING`) run already exists
   for the same tenant + run type (`existsByTenantIdAndRunTypeAndStatusIn`).
4. Creates the run, adds up to `maxCases` cases, and (unless `dryRun`) executes it, persisting
   run/case/result through existing OP-CAP-19 entities.
5. Read-only status/results are exposed via the existing OP-CAP-19 GET routes.

## 9. Tests run (targeted, offline)

- `AiAdvisoryRuntimeAssistServiceStage20Test` — 7 tests (exact-match-first, invalidated excluded,
  maxHints clamp, tenant isolation, no-broadening, bounded/safe summaries, no mutation). **PASS**
- `AiMemoryEvaluationBatchRunnerServiceStage20Test` — 9 tests (manual run/case/result, maxCases enforced,
  maxResultsPerCase clamped, bounded failure reason, tenant isolation, unsupported source rejected, empty
  cases rejected, duplicate active run rejected, dry run). **PASS**
- `ApiPermissionInterceptorPermissionTest` — 121 tests (incl. 5 new OP-CAP-20 permission cases). **PASS**
- Regression: `AiAdvisoryMemoryRetrievalServiceStage19Test` (9), `AiMemoryEvaluationServiceStage19Test` (9),
  and the wider trust suite present in surefire — all **PASS**.

## 10. Limitations

- `RECENT_CORRECTIONS` / `RECENT_TRUST_EVENTS` case sources are scaffolded (enum only) and explicitly
  rejected at runtime to avoid an unbounded scan; a future stage can add bounded query sources.
- The concurrency guard is at **tenant + run-type** granularity (no per-`evaluationKey` field exists on
  the run entity). It blocks duplicate active runs of the same type but not finer-grained keys.
- `contextId` is treated as an advisory correlation reference only; runtime assist does not load or join
  any concrete trust/validation-review business entity (deliberate, to keep the surface read-only and
  decoupled). Using it as a memory `lookupKey` is supported via the explicit `lookupKey` parameter.
- No new scheduler is wired; batch runs are manual-trigger only.

## 11. Next recommended stage

OP-CAP-21 — wire bounded, tenant-scoped query sources for `RECENT_CORRECTIONS` / `RECENT_TRUST_EVENTS`
(reusing existing operator-correction and trust-event read models), and optionally introduce a
config-gated, default-disabled scheduled trigger for the bounded evaluation runner with explicit
tenant/size caps. No migration is anticipated unless a per-`evaluationKey` idempotency field is added.

---

### Migration

**No migration needed.** OP-CAP-20 reuses the OP-CAP-19 `ai_memory_evaluation_run/case/result` tables
(run status, run type, case/result records, bounded failure reason) and adds no new persistent fields,
tables, or indexes. Latest applied migration remains **V52**; the next migration, if ever required, is V53.
