# OP-CAP-19 — Transactional Trust/AI Event Auto-Publishing + Advisory Memory Retrieval Ranking + Projected Memory Evaluation Harness

## 1. Objective

Turn the OP-CAP-18 manual event/projector foundation into a controlled runtime loop by adding three
additive, tenant-scoped layers:

- **Layer A — Transactional Event Auto-Publishing Hooks:** deterministic OP-CAP-17A–17F services emit a
  bounded `TrustAiDomainEvent` after a safe, persisted state transition.
- **Layer B — Advisory AI Memory Retrieval Ranking:** bounded, rule-scored retrieval over governed
  OP-CAP-17F AI memory so future runtime components can ask for safe, approved hints.
- **Layer C — Projected Memory Evaluation Harness:** deterministic, local governance evaluation that
  measures whether governed memory helps without becoming authoritative.

OP-CAP-19 **builds on** OP-CAP-18; it does not rewrite it.

## 2. Relation to OP-CAP-18

The loop is now:

```
deterministic backend command/service persists an outcome
  → OP-CAP-19 Layer A publishes a bounded TrustAiDomainEvent (idempotent)
  → OP-CAP-18 projector runtime may process it
  → OP-CAP-17F governed AI memory may be created/superseded
  → OP-CAP-19 Layer B advisory retrieval can rank memory hints for future use
  → the deterministic service still makes the final decision
```

OP-CAP-19 reuses the existing `TrustAiEventPublisherService.publishOnce(...)`, `TrustAiEventType`,
`AiMemorySourceType`, and `AiMemoryRecord` read model. No new event or projector tables were added.

## 3. Source-of-truth vs advisory memory

Deterministic backend command/application services remain the **only** source of truth for orders,
quotes, prices, stock, payments, counterparty trust, and approval status. AI memory and advisory
retrieval are **advisory and low-authority** — they never write business/ERP data, never gate approval,
and never override a deterministic decision. Every retrieval hint is `advisoryOnly = true`.

## 4. Auto-publishing hook model (Layer A)

`TrustAiEventAutoPublishService` is a thin adapter with one hook per event type. It is called from inside
the source service's existing `@Transactional` method, *after* the authoritative row is persisted, so the
event commits in the same transaction as the state change. It only ever publishes — it never processes
events, never derives memory, and is never called by the projector, so it cannot create an
event→projector→event cycle.

Wired this stage (minimal, safe, central):

- `TrustRiskDecisionService.evaluate(...)` → `TRUST_RISK_DECIDED`
- `TrustRiskDecisionService.overrideDecision(...)` → `TRUST_RISK_OVERRIDDEN`
- `DocumentTrustService.evaluate(...)` → `DOCUMENT_TRUST_COMPLETED`

Available but intentionally **not** auto-wired (documented to keep the loop acyclic / avoid recursive
fan-out, and to avoid broadening this stage's blast radius into 17B/17C/17F services):
`publishCounterpartyTrustUpdated`, `publishPaymentObligationUpdated`, `publishPaymentAllocationRecorded`,
`publishAiMemoryInvalidated`, `publishRuntimeTraceRecorded`. All are unit-tested at the adapter level.

## 5. Idempotency model

Idempotency keys are deterministic and tenant-safe (never a random UUID for a deterministic source):

| Event | Key |
|-------|-----|
| `DOCUMENT_TRUST_COMPLETED` | `document-trust-completed:<runId>` |
| `COUNTERPARTY_TRUST_UPDATED` | `counterparty-trust-updated:<profileId>:<version>` |
| `PAYMENT_OBLIGATION_UPDATED` | `payment-obligation-updated:<obligationId>:<version>` |
| `PAYMENT_ALLOCATION_RECORDED` | `payment-allocation-recorded:<allocationId>` |
| `TRUST_RISK_DECIDED` | `trust-risk-decided:<decisionId>` |
| `TRUST_RISK_OVERRIDDEN` | `trust-risk-overridden:<decisionId>:<overrideId>` |
| `AI_MEMORY_INVALIDATED` | `ai-memory-invalidated:<recordId>` |
| `AI_RUNTIME_TRACE_RECORDED` | `runtime-trace-recorded:<traceId>` |

`publishOnce` returns the existing event on a duplicate key; a concurrent-insert
`DataIntegrityViolationException` is caught and treated as idempotent success, so a hook never duplicates
an event and never corrupts the business transaction.

## 6. Retrieval ranking model and score formula (Layer B)

`AiAdvisoryMemoryRetrievalService` performs deterministic, tenant-scoped, bounded retrieval over `ACTIVE`
memory only — superseded / invalidated / expired records are excluded by querying the `ACTIVE` status.
This is **not** vector/semantic search.

Score (clamped to 0..100):

| Component | Range | Rule |
|-----------|-------|------|
| Authority | 0..25 | HUMAN_APPROVED 25, SYSTEM_DERIVED 20, HIGH 18, MEDIUM 12, LOW 6 |
| Confidence | 0..25 | `round(confidence × 25)` |
| Namespace/task match | 10 or 20 | 20 when the task type maps to the record's namespace, else 10 |
| Source/key match | 0..20 | exact key match 20 (`EXACT_KEY_MATCH`), else same source type 10 (`SAME_SOURCE_TYPE`) |
| Freshness | 0..10 | ≤7d 10, ≤30d 6, ≤90d 3, else 0 |

An **exact `lookupKey` match always ranks ahead of non-matching candidates** (primary sort key), then ties
fall back to the composite score, then recency, then id. HUMAN_APPROVED memory can rank highest but remains
advisory. Reason codes (`HUMAN_APPROVED`, `EXACT_KEY_MATCH`, `SAME_SOURCE_TYPE`, `TASK_NAMESPACE_MATCH`,
`HIGH_CONFIDENCE`, `RECENT_MEMORY`, `SYSTEM_DERIVED`, plus the documented `EXCLUDED_*` / `LOW_CONFIDENCE`
codes) make ranking auditable.

`maxResults` is clamped to 1..25. `minConfidence` defaults to the OP-CAP-17F `MIN_USABLE_CONFIDENCE`
(0.50) and can be lowered per request. `includeSuperseded` / `includeInvalidated` are accepted but
reserved — advisory retrieval only ever serves `ACTIVE` governed memory.

## 7. Evaluation harness model (Layer C)

`AiMemoryEvaluationService` + `ai_memory_evaluation_run/case/result` provide deterministic, local
governance evaluation over Layer B. A run holds cases; each case probes advisory retrieval and asserts an
expectation:

- `EXPECT_TOP_MATCH` — top hint key equals the expected key.
- `EXPECT_EXCLUDED_INVALIDATED` / `EXPECT_EXCLUDED_SUPERSEDED` — an ineligible key is absent from results.
- `EXPECT_TENANT_ISOLATED` — a foreign-tenant key never leaks into a tenant's results.
- `EXPECT_SCORE_ABOVE_THRESHOLD` — the top score meets a minimum.

Results record top-hint metadata, boolean assertions, and a bounded failure reason. Cases per run are
clamped to ≤ 200; per-case `maxResults` reuses the Layer B clamp. Evaluation **never mutates memory**
(it does not even increment the access counter — retrieval is read-only), never creates business records,
and never calls an external model.

## 8. Tenant isolation guarantees

Every table carries `tenant_id NOT NULL`. Every service finder is tenant-scoped (`...AndTenantId`), every
query is bounded by a clamped `Pageable`, and tenant is resolved from `TenantContext` on the REST surface
(never client-supplied). Retrieval queries one tenant's `ACTIVE` memory only; the evaluation harness'
`EXPECT_TENANT_ISOLATED` case asserts a foreign key cannot appear in another tenant's results.

## 9. Forbidden payloads

No raw OCR, raw document text, raw prompt text, raw email body, raw customer message, raw bank/card data,
credentials, secrets, or stack traces. Event/evaluation text columns are bounded VARCHAR. The Layer A
sanitizer collapses control characters/whitespace and drops stack-trace-shaped summaries to `null`. Hints
expose only memory metadata (namespace, key, source type, authority, confidence, bounded summary, score,
reason codes) — no internal value hashes.

## 10. Permissions

- Layer A: no new permission (publishing is internal to deterministic services).
- Layer B: `POST /api/v1/trust/ai-memory/advisory-retrieval` requires `TRUST_AI_MEMORY_READ` (a read,
  even though it is a POST carrying a query body) — never the write/invalidate permissions.
- Layer C (new permissions):
  - `TRUST_AI_MEMORY_EVALUATION_READ` — read runs/cases/results.
  - `TRUST_AI_MEMORY_EVALUATION_WRITE` — create runs/cases.
  - `TRUST_AI_MEMORY_EVALUATION_RUN` — execute a run (strongest; generic AI-memory read/write never grants it).

Routes (under `/api/v1/trust/ai-memory/evaluations`): create run/case → WRITE, `/execute` → RUN,
GET → READ.

## 11. Performance / resource boundaries

No Kafka, no microservices, no vector DB, no new infrastructure, no external AI calls, no model training.
Retrieval fetches at most `min(maxResults×3+5, 50)` rows per namespace over indexed columns (V52 adds
`(tenant_id, source_type, source_id)` and `(tenant_id, authority_level, confidence)`; V49 already indexed
namespace/status/key/expiry). Evaluation runs process at most 200 cases; all list APIs are page/size
clamped (default 25, max 100).

## 12. Live PostgreSQL verification result

Verified against `infra/docker/docker-compose.test.yml` service `postgres-test`
(`localhost:15432`, db `orderpilot_test`) with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`,
`SPRING_FLYWAY_ENABLED=true`:

- Spring Boot started successfully (`Started ... Application in ...`), so Flyway migrated and Hibernate
  validated all entities against the live Postgres schema.
- `flyway_schema_history` top successful row is **V52** (`ai advisory memory retrieval evaluation`).
- Tables `ai_memory_evaluation_run`, `ai_memory_evaluation_case`, `ai_memory_evaluation_result` exist.
- Indexes `idx_ai_memory_record_tenant_source`, `idx_ai_memory_record_tenant_authority_confidence`,
  `idx_ai_memory_eval_run_tenant_status_created`, `idx_ai_memory_eval_result_tenant_run` exist.

## 13. Tests run

- `TrustAiEventAutoPublishServiceStage19Test` (9) — Layer A publish-once/idempotent/sanitized/tenant-safe.
- `AiAdvisoryMemoryRetrievalServiceStage19Test` (9) — Layer B ranking/exclusion/isolation/clamp/advisory.
- `AiMemoryEvaluationServiceStage19Test` (9) — Layer C run/case/result, no-mutation, clamps, paging.
- `ApiPermissionInterceptorPermissionTest` (116, +11 OP-CAP-19) — advisory-retrieval + evaluation routes.
- Regression: 17A (`DocumentTrustFoundationStage17ATest` 17, `DocumentTrustControllerStage17ATest` 2),
  17D (`TrustRiskDecisionServiceStage17DTest` 13, `TrustRiskDecisionControllerStage17DTest` 4),
  17F (`AiMemoryGovernanceServiceStage17FTest` 13, `TrustAiMemoryControllerStage17FTest` 6),
  18 (`TrustAiProjectionControllerStage18Test` 4, `OperatorCorrectionLearningServiceStage18Test` 6).
- `mvn -o compile` and `mvn -o test-compile` clean.

## 14. Limitations

- Layer A is wired into 17A document-trust and 17D risk-decision/override only. The 17B/17C/17F hook
  methods exist and are tested at the adapter level but are not auto-wired this stage.
- Advisory retrieval serves `ACTIVE` memory only; `includeSuperseded`/`includeInvalidated` are reserved
  flags, not yet honored.
- No frontend was added.
- The 200-case-per-run guard is enforced in the service (`ConflictException`); only the lighter per-case
  `maxResults` clamp is asserted by an automated test.

## 15. Next recommended stage

OP-CAP-20: wire the advisory retrieval into a concrete read-only runtime assist surface (e.g. operator
review or extraction assist) that *consumes* ranked hints while the deterministic service still decides,
plus a scheduled bounded evaluation-run job to track retrieval-quality regression over time.
