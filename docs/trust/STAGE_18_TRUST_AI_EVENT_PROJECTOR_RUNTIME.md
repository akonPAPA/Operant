# OP-CAP-18 — Trust/AI Event Projector Runtime + Operator Correction Learning Loop

## 1. Objective

Connect the OP-CAP-17A–17F deterministic trust/payment/document/risk/analytics layers and the advisory
OP-CAP-17F AI memory layer through a controlled, tenant-scoped, idempotent internal **event → projector →
governed AI memory** learning loop. This stage adds: a durable internal domain-event/outbox record,
idempotent projector checkpoints, safe projectors that populate/supersede AI memory from approved
deterministic events, operator correction learning records, deterministic correction-pattern projection,
bounded retry/dead-letter handling, and read-only observability APIs.

## 2. Why this stage exists after 17F

17F gave us a governed, tenant-scoped, advisory AI memory store but no *safe pipeline* to fill it. 18 is
that pipeline: approved deterministic outcomes (operator corrections, trust/payment/document events) become
durable internal events, and a controlled projector turns the safe ones into advisory memory — without any
uncontrolled background daemon, model training, or external provider call. It closes the loop
*operator correction / approved event → internal event → idempotent projector → sanitized tenant memory →
future advisory retrieval*.

## 3. Source-of-truth vs AI memory authority

- Operational write models (orders, quotes, validations, payments, trust decisions, approvals) remain the
  **single source of truth**. Deterministic services always outrank AI memory.
- AI memory is **advisory and low-authority**. Projectors may create AI memory records, runtime traces,
  projection checkpoints, and read-model updates. They **never** create/approve orders, quotes, payments,
  trust decisions, ERP/PSP writes, or any authoritative business state.
- Any future change to business state must go through existing command services and approval gates — not
  this stage.

## 4. Event model

`TrustAiDomainEvent` (`trust_ai_domain_event`): tenant-scoped, durable, unique per (tenant, idempotency
key). Holds `eventType`, `sourceType`/`sourceId` (pointer to an existing safe record), optional
`subjectType`/`subjectId`, a **bounded** `payloadSummary` (never raw payload), status, retry/failure
counters. Event types: `DOCUMENT_TRUST_COMPLETED`, `TRUST_RISK_DECIDED`, `TRUST_RISK_OVERRIDDEN`,
`PAYMENT_OBLIGATION_UPDATED`, `PAYMENT_ALLOCATION_RECORDED`, `COUNTERPARTY_TRUST_UPDATED`,
`OPERATOR_CORRECTION_RECORDED`, `AI_MEMORY_INVALIDATED`, `AI_RUNTIME_TRACE_RECORDED`. Status:
`PENDING`/`PROCESSING`/`PROCESSED`/`FAILED`/`SKIPPED`/`DEAD_LETTERED`. Publishing
(`TrustAiEventPublisherService`) is idempotent: a duplicate (tenant, idempotency key) returns the existing
event.

## 5. Projector model

`TrustAiProjectorRuntimeService` processes events through `AiMemoryEventProjector`, recording a
per-(event, projector) `TrustAiProjectionCheckpoint` (`trust_ai_projection_checkpoint`), unique on
(tenant, projector, event) and (tenant, projector, idempotency key). A `COMPLETED`/`SKIPPED` checkpoint
makes re-projection a no-op. Processing is **explicit and tenant-scoped** — there is **no background
thread/daemon/scheduler** (none exists in the project; a manual service call/endpoint drives it). Each
event is processed in its **own transaction** (`REQUIRES_NEW` via a lazy self-proxy) so one failure never
rolls back the rest. Outcomes: `PROJECTED`/`ACKNOWLEDGED` → event `PROCESSED`; `SKIPPED` → event `SKIPPED`;
exception → bounded `FAILED` then `DEAD_LETTERED` at the retry cap.

## 6. Operator correction learning loop

`OperatorCorrectionLearningRecord` (`operator_correction_learning_record`) captures sanitized correction
metadata. `OperatorCorrectionLearningService`: `recordCorrection` (hashes raw previous/corrected values
with SHA-256 and discards them; never logs raw values; deterministic `learningEligible`),
`approveCorrectionForLearning` (operator gate → publishes an idempotent `OPERATOR_CORRECTION_RECORDED`
event for the projector), `rejectCorrection`, and bounded list/get. Status:
`RECORDED`/`APPROVED_FOR_LEARNING`/`PROJECTED_TO_MEMORY`/`REJECTED`/`SUPERSEDED`. Correction types map to
namespaces (PRODUCT_ALIAS→PRODUCT_ALIAS_HINT, CUSTOMER_ALIAS→COUNTERPARTY_PATTERN,
DOCUMENT_FIELD_MAPPING→EXTRACTION_CORRECTION, PAYMENT_MATCHING_HINT→PAYMENT_MATCH_HINT,
TRUST_REASON_RECLASSIFICATION→TRUST_SIGNAL_HINT, VALIDATION_RULE_CLARIFICATION→VALIDATION_EXPLANATION,
BOT_RESPONSE_CORRECTION→BOT_CONVERSATION_SUMMARY, IMPORT_MAPPING_CORRECTION→OPERATOR_CORRECTION_PATTERN).
`PRICE_OR_STOCK_CORRECTION_BLOCKED` is recorded for traceability but **can never be approved or projected
into authoritative memory** (approval throws).

## 7. Memory projection policy

- Projectors create memory only via the 17F `AiMemoryGovernanceService` (never bypassing it) and run the
  `AiMemoryPolicyService` sanitization before any write (governance re-validates as defence in depth).
- **Operator-approved** corrections → `HUMAN_APPROVED` memory. **Automated** events (trust/payment/document)
  → only `MEDIUM`/`SYSTEM_DERIVED` advisory hints, and only from a bounded, sanitized `payloadSummary`
  (otherwise `SKIPPED`). No automated event ever creates `HIGH`/`HUMAN_APPROVED` memory.
- Deterministic memory keys (e.g. `product-alias:<fieldKey>:<correctedValueHash>`) mean a repeated
  correction **supersedes** the same record (one ACTIVE version) rather than duplicating it.
- `AI_MEMORY_INVALIDATED`/`AI_RUNTIME_TRACE_RECORDED` are acknowledged only — they never (re)create memory.
- `COUNTERPARTY_TRUST_UPDATED`/`TRUST_RISK_OVERRIDDEN` are not projected in this stage (`SKIPPED`, no safe
  deterministic mapping yet).

## 8. Tenant isolation rules

Every row carries `tenant_id` (NOT NULL, FK to `tenant`). Every event/checkpoint/correction finder and the
pending batch are tenant-scoped; processing/reading another tenant's event id throws `NotFound`. There is
no cross-tenant batch, no global memory, and no cross-tenant projection.

## 9. Idempotency / retry / dead-letter behaviour

- **Publish idempotency**: duplicate (tenant, idempotency key) returns the existing event.
- **Projection idempotency**: the per-(event, projector) checkpoint prevents double-projection; a terminal
  event is a no-op; memory upsert (find-active → supersede, else create) avoids duplicates.
- **Retry**: `MAX_RETRY = 3` with a fixed `+5m` backoff; FAILED events are only re-offered while under the
  cap and past `next_retry_at`.
- **Dead-letter**: at the retry cap the event becomes `DEAD_LETTERED` (terminal) — never infinite-looped.
  Dead-letter is modelled with the event status + checkpoint failure columns; **no separate dead_letter
  table is needed** (documented choice — keeps the schema lean; the read API serves `DEAD_LETTERED` events).

## 10. Permissions

| Action | Path | Permission |
|---|---|---|
| Read events / checkpoints / dead-letter | `GET /api/v1/trust/ai-events**` | `TRUST_AI_EVENT_READ` |
| Process (batch / one) | `POST /api/v1/trust/ai-events/**` | `TRUST_AI_EVENT_PROCESS` |
| Read corrections | `GET /api/v1/trust/operator-corrections**` | `TRUST_OPERATOR_CORRECTION_READ` |
| Record correction | `POST /api/v1/trust/operator-corrections` | `TRUST_OPERATOR_CORRECTION_WRITE` |
| Approve for learning | `POST .../{id}/approve-learning` | `TRUST_OPERATOR_CORRECTION_APPROVE` |
| Reject learning | `POST .../{id}/reject-learning` | `TRUST_OPERATOR_CORRECTION_REJECT` |

Generic `TRUST_READ` grants none of these; read ≠ process; write ≠ approve; approve ≠ reject. Rules are
evaluated before the generic `/api/v1/trust → TRUST_READ` prefix mapping. Verified in
`ApiPermissionInterceptorPermissionTest`.

## 11. Security rules and forbidden payloads

No raw documents, OCR text, prompts, customer messages, secrets, card data, bank credentials, raw private
keys, or unbounded payloads are stored anywhere in this stage. All text columns are bounded VARCHAR. Raw
operator previous/corrected values are SHA-256 hashed and discarded (never persisted or logged). Projector
writes pass the 17F sanitization guard, so credential-like payloads (e.g. `Authorization: Bearer …`) are
rejected and produce no memory. AI never writes business/ERP/PSP state; corrections require operator
approval before becoming `HUMAN_APPROVED` advisory memory.

## 12. Performance / resource boundaries

Every query is tenant-scoped, indexed, and limit-clamped (default 25 / max 100; batch default 50 / max 200;
TTL/retry scans bounded). No new infrastructure: no Kafka, no microservices, no vector DB, no async daemon,
no new dependency. Indexes per V51 cover status/type/source/next-retry lookups and the unique idempotency
keys.

## 13. Live PostgreSQL verification result

Clean DB (`docker compose -f infra/docker/docker-compose.test.yml down -v && up -d postgres-test`,
PostgreSQL 16), then core-api with `SPRING_FLYWAY_ENABLED=true` + `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`
against `localhost:15432`:
- Flyway: `Successfully applied 51 migrations … now at version v51`.
- **`Started OrderPilotApplication`** — Hibernate schema validation passed (no mismatch).
- Tables present: `trust_ai_domain_event`, `trust_ai_projection_checkpoint`,
  `operator_correction_learning_record` (no `projection_dead_letter` by design).
- Unique constraints present: `ux_trust_ai_domain_event_tenant_idem`,
  `ux_trust_ai_checkpoint_tenant_projector_event`, `ux_trust_ai_checkpoint_tenant_projector_idem`.

## 14. Tests run

- **Runtime/projector** (`TrustAiProjectorRuntimeServiceStage18Test`, 12): publish validation +
  idempotency; bounded tenant batch; checkpointed idempotent processing; retry → dead-letter; tenant
  isolation; operator-correction → `HUMAN_APPROVED` memory; repeated-correction supersede; payment
  credential-like payload rejected (no memory) + safe `MEDIUM` hint; trust-risk advisory-only; invalidation
  acknowledged (no memory); unsupported type `SKIPPED` with checkpoint.
- **Correction learning** (`OperatorCorrectionLearningServiceStage18Test`, 6): hashes not raw values;
  approve publishes + marks eligible; reject blocks approval; price/stock recorded but not approvable;
  low-confidence eligible only after approval; tenant isolation.
- **Controllers** (`TrustAiProjectionControllerStage18Test` 4, `OperatorCorrectionLearningControllerStage18Test`
  4): delegation, tenant-scoped paging/filtering, dead-letter view.
- **Permissions** (`ApiPermissionInterceptorPermissionTest`, +12): read/process/write/approve/reject
  distinct; generic `TRUST_READ` insufficient.
- **Regression**: 17A–17F service + controller suites green (83 tests). `mvn -o compile`/`test-compile`
  clean.

## 15. Limitations

- Projection is driven by an explicit, tenant-scoped service call/endpoint — no scheduler/daemon. Wiring
  the 17A–17F command services to auto-publish events on every approved outcome is intentionally **not**
  done here (kept as the natural next hook to avoid coupling/parasitic complexity this stage); events are
  published explicitly (and on correction approval).
- Automated advisory-hint projections (trust/payment/document) deliberately store only the bounded
  `payloadSummary` provided by the publisher — they do not re-read upstream records.
- No `projection_dead_letter` table (status-based dead-lettering); no purge/replay-all admin endpoint.
- Correction reads live on `OperatorCorrectionLearningService` (the owner) rather than duplicated in
  `ProjectionQueryService`, to avoid duplication.

## 16. Next stage recommendation

**OP-CAP-19** — wire the 17A–17F command/approval services to publish `TrustAiDomainEvent`s automatically
on approved outcomes (transactional outbox dispatch), add advisory-retrieval ranking that consumes this
governed memory in extraction/validation suggestions (still non-authoritative), and add an evaluation
harness for projected-memory quality. (Not started — out of scope for OP-CAP-18.)
