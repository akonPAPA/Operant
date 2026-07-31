# OPERANT ARCHITECTURE FREEZE 0 — Master Document

> **State-2-compatible target modular-monolith architecture for Operant (historical: OrderPilot).**
> Code-grounded target design that can be implemented incrementally over the existing Core API.
> This document is **architecture documentation only**. It changes no production code, migration, test, config, or workflow.

---

## 1. Repository identity and source SHA

| Field | Value |
|---|---|
| Repository | `github.com/akonPAPA/Operant.git` (historical name: OrderPilot) |
| Branch | `main` |
| Source commit (HEAD) | `c11f7e87e3aa1d9ab266c3d2ddaeeedfbe90950a` |
| Upstream | `origin/main` = `origin/HEAD` (in sync) |
| Worktree | **clean** (`git status --short` empty; `git diff --check` clean) |
| Last merged PR at HEAD | #297 `feat(control): add backup artifact authority and audit persistence` |
| Authoritative phase record | `OPERANT_PRODUCTION_EXECUTION_STATE.md` (document_version 12) — phase **1**, active **P1-E bounded Control API + operantctl**, next **P1-F Connector Gateway protocol**, then P1-G operant-agent, P1-H recovery/observability |
| Migrations applied | V1 … V69 (69 Flyway scripts) |
| Core-api Java sources | 969 files: `domain` 439, `application` 331, `api` 130, `security` 45, `common` 18, `infrastructure` 6 |

**Compatibility with the requested "full-depth audit baseline":** the task references several mandatory
documents that are **not present in the repository** (see §22). No prior full-depth audit artifact was
found in-repo that binds to HEAD `c11f7e8`. Therefore old findings are **not** reused as current;
every architectural claim here is re-derived from HEAD source. Compatibility label for this freeze:
**PARTIAL** — code is fully readable and reconciled; the external audit/roadmap documents the prompt
names could not be located to cross-check against.

### 1.1 State-1/2/3 vs in-repo Phase-1 reconciliation

The prompt frames the target as "State 2". The repository does **not** contain
`OPERANT_THREE_STATE_EXECUTION_ROADMAP_V2.md` (the document that would define State 1/2/3). The
authoritative in-repo roadmap axis is **Phase 1 (P1-A … P1-H)** in `OPERANT_PRODUCTION_EXECUTION_STATE.md`.
This freeze maps the two axes as follows, and treats the mapping as **NEEDS_OWNER_DECISION** where the
roadmap document is required to confirm intent:

| Prompt "State" | In-repo evidence | Mapping used here |
|---|---|---|
| State 2 target deployment (BFF, Core modular monolith, AI/doc worker, **Connector Gateway**, outbound-only **operant-agent**, Postgres, Redis, object storage) | P1-D hardened Linux topology (merged #282); P1-E Control API/operantctl; P1-F Connector Gateway and P1-G operant-agent are the declared next capabilities | Prompt "State 2" ≈ completion of Phase-1 production topology through P1-G. Treated as the target of this freeze. |
| State 3 | No in-repo definition | Future extraction seams only (View 15). Not designed here. |

---

## 2. Scope and non-scope

**In scope (this freeze):** architecture inventory; current-state reconstruction; business-capability
modeling; module-boundary design; canonical business-flow normalization; connector taxonomy;
data-ownership design; state-machine catalogue; transaction/consistency design; performance/workload
design; diagrams; ADRs; current-to-target migration planning; identification of active architectural
blockers.

**Explicitly NOT in scope:** feature implementation; source refactoring; package relocation; migration
creation; microservice extraction; frontend redesign; dependency installation; speculative greenfield
architecture. No production code, migration, test, config, or workflow is modified. No commit/push/PR.

---

## 3. Current architecture diagnosis

Operant at `c11f7e8` is already a **modular monolith** (`apps/core-api`, Java 21 / Spring Boot) with a
Next.js dashboard/BFF, an advisory Python AI worker seam, PostgreSQL, Redis, object storage, and a
newly-added bounded control plane (`apps/operantctl`). The safety spine is real and enforced in code:

- **Backend-owned authority.** `ApiRouteSecurityPolicy` is the single route→permission source, with a
  global `/api/**` default-deny; unclassified/wrong-method/unknown sub-paths fail closed
  (`ApiRouteSecurityPolicy.java:67-92, 338-339`). Actor/tenant are server-resolved
  (`RequestActorResolver`, `X-Tenant-Id`/TenantContext), never body-owned (OP-CAP-31/32).
- **Transactional outbox exists and is external-gated.** `OutboxEvent` transitions
  `PENDING → PUBLISHED_INTERNAL_ONLY | SKIPPED_EXTERNAL_DISABLED`
  (`domain/integration/OutboxEvent.java:37-46`). No external publisher is active — external effects are
  disabled by construction.
- **Idempotency + request integrity** are first-class (`common/idempotency/IdempotencyService`,
  `IdempotencyRecord`, V55 `idempotency_request_integrity`; `ConnectorIdempotencyService`).
- **Audit** is append-only via service calls, no public mutation API (`domain/audit/AuditEvent`,
  V66 polymorphic principal).
- **Connectors default DRAFT + READ_ONLY**, external writes disabled
  (`integration-connector-foundation.md`); the only real executor is the bounded
  `PROCESSING_JOB_STATUS_REPAIR` (OP-CAP-54), everything else is dry-run/stub.
- **Four access planes** are already distinguishable in the route policy (§15).

**Where the architecture is drifting (the reason this freeze exists):** the *business surface* has grown
faster than its module boundaries. Symptoms, all code-grounded:

1. **Controller sprawl / duplicate business paths** (§4). 76 controllers, with several overlapping
   intake→RFQ, validation-review, webhook, quote-draft, and analytics surfaces.
2. **Legacy route namespaces coexist.** `/api/stage8/*` and `/api/stage9/*` live beside `/api/v1/*` for
   the same capabilities (`ApiRouteSecurityPolicy.java:20-22, 61-64, 149-153`).
3. **Quote surface is spread across many controllers** (`DraftQuoteController`, `QuoteReviewController`,
   `WorkspaceController`, `RfqHandoffDraftQuoteController`, `QuoteTransactionController`,
   `QuoteTransactionConversionController`) — a real *Quote-god-module* risk.
4. **`draft_quote.status` is an unconstrained `VARCHAR(40)`** with no DB CHECK / JPA enum / central
   allowlist (checkpoint, OP-CAP-36 closure) — the Quote state machine is implicit, not enforced.
5. **`workspace` domain (34 files) mixes Quote, Order, draft review, and exception cockpit** — Work
   Management and the commercial aggregates are not yet separated owners.
6. **`trust` is the single largest domain (86 files)** spanning AI advisory memory, risk decisions,
   analytics read-models, and event projection — multiple concerns under one package.

None of these is a safety break; all are **modularity debt** that this freeze converts into an
enforceable target.

---

## 4. Current duplicate / overlapping business paths (evidence-grounded)

| # | Overlap | Current controllers/paths (evidence) | Target resolution |
|---|---|---|---|
| D1 | Inbound webhook entry | `ChannelWebhookController`, `WebhookController`, `BotTelegramWebhookController`, `ChannelGatewayController`; `LegacyWebhookController*` (tests) | One **Canonical Intake Pipeline** behind category-typed adapters (View 03). MERGE. |
| D2 | Intake → RFQ / handoff | `ChannelRfqHandoffController`, `DemoRfqHandoffController`, `RfqHandoffDraftQuoteController`, `ChannelBotBridgeController` | `inquiry` module owns Inquiry/RFQ; demo path becomes a thin adapter seam. MERGE + DEPRECATE demo. |
| D3 | Validation review surface | `ValidationController`, `ValidationReviewController`, `ValidationWorkspaceActionController`, `OperatorReviewController`, `ExtractedRequestValidationController`, `ExtractionValidationController`, `ExtractionAdvisoryValidationController`, `AiValidationHandoffController` | `decisiontrace` + `workmanagement` split; one canonical review case. MERGE/SPLIT. |
| D4 | Quote draft/review/convert | `DraftQuoteController`, `QuoteReviewController`, `WorkspaceController`, `RfqHandoffDraftQuoteController`, `QuoteTransactionController`, `QuoteTransactionConversionController` | `quote` owns Quote aggregate; `ordermanagement` owns conversion; `workmanagement` owns review UX contract. SPLIT. |
| D5 | Analytics / projections | `AnalyticsController`, `CommerceAnalyticsController`, `CommerceIntelligenceController`, `Stage8AnalyticsController`, `Stage8ValueAnalyticsController`, `TrustAnalyticsController`, `CommandCenterController` | `commerceintelligence` + `customerprojection` read-models only; never transactional truth. MERGE. |
| D6 | Legacy vs v1 namespace | `/api/stage8/*`, `/api/stage9/*` beside `/api/v1/*` | Collapse onto `/api/v1/*`; keep legacy as compatibility shim until FE migrated. DEPRECATE. |

---

## 5. Target State-2-compatible architecture (summary)

A **strict modular monolith at the business core** (`apps/core-api`) with isolated non-core runtimes:

```
Next.js Web Dashboard / BFF   →  Core API (modular monolith)  →  PostgreSQL (single authoritative DB)
        (browser auth: OIDC)         ├─ platform/control band       Redis (workload-partitioned)
                                     ├─ intake/evidence band        Object Storage (blobs/raw payloads)
AI / Document Worker (advisory) ─────┤ commercial / O2C band
Connector Gateway (P1-F seam)  ──────┤ integration / delivery band
operant-agent (outbound-only, P1-G) ─┤ projections / analytics band
                                     └─ (external providers behind category-typed adapters)
```

Design invariants (all enforceable — see `OPERANT_DEPENDENCY_RULES.md`):

1. Business core stays one deployable. No business microservice is extracted without a proven failure/
   scaling/deployment boundary (§18, service-extraction gate).
2. Every business state has **exactly one authoritative owner module** (§6, §10, ownership matrix).
3. Providers/connectors/workers/BFF/agents are **adapters**, never authority holders.
4. Canonical internal flows replace provider-specific duplicated flows (§11).
5. Adapters → application orchestration → domain rules → persistence → projections are separated layers.
6. Atomic PostgreSQL transactions only where one business invariant requires them; audit+outbox commit
   atomically with the business mutation (§13, §14).
7. At-least-once external delivery + idempotent effect; **no exactly-once claim** (§13).

---

## 6. Module catalogue (target)

Full detail in `OPERANT_MODULE_CATALOG.tsv`. **30 target business modules** in 5 colour bands plus the
external/infra bands. Status labels are per the truth taxonomy.

**Platform & Control (band A)** — `identityaccess`, `tenantorganization`, `tenantconfiguration`,
`runtimecontrol`, `auditoutbox`, `supportcontrol`, `observability`, `developerplatform`(=operantctl, separate deployable).

**Intake & Evidence (band B)** — `channelintake`, `documentevidence`, `inquiry`, `aiadvisory`,
`decisiontrace`, `workmanagement`.

**Commercial & O2C (band C)** — `customerparty`, `catalog`, `inventory`, `commercialpolicy`, `quote`,
`ordermanagement`, `fulfillment`, `billingar`, `paymentreconciliation`, `claimscollections`.

**Integration & Delivery (band D)** — `integrationcontrol`, `connectorexecution`, `communicationdelivery`,
`securitydelivery`, `customerprojection`, `commerceintelligence`, `tenantimplementation`.

Rejections/merges from the candidate list:
- `claimscollections` → **DEFERRED** (no code today).
- `securitydelivery` (MFA/security messaging) → **TARGET_STATE_2** — must be a *separate* delivery owner
  from `communicationdelivery`; only OIDC/session security exists today (P1-C), no MFA delivery yet.
- `billingar` → **CURRENT_PARTIAL/NEEDS_OWNER_DECISION** — only `payment` obligation intelligence exists;
  invoice/AR mirror is not yet an owned aggregate.
- `commerceintelligence` absorbs the six analytics controllers (D5) as **read-models only**.

Each module box (owns / public API / publishes / depends-on / must-never) is specified per module in
`OPERANT_MODULE_CATALOG.tsv` and rendered in `02_CORE_MODULE_MAP.mmd`.

---

## 7. Ownership principles

- **One writer per state.** A business state (Quote, Order, PaymentAllocation, Inventory authority,
  Approval, Audit) has one module that may mutate it; all others read via that module's public API.
- **No cross-module repository access, no cross-module entity mutation.** Contracts are IDs, value
  objects, commands, queries, results, and events — never JPA entities.
- **Work Management is not a second owner.** `workmanagement` orchestrates operator review UX and issues
  commands to `quote`/`ordermanagement`; it never stores authoritative Quote/Order/Payment/Inventory.
- **AI and connector output is untrusted advisory.** `aiadvisory`/`decisiontrace` produce typed results;
  deterministic validation in the owning commercial module decides.
- **Projections are not truth.** `commerceintelligence`, `customerprojection` are rebuildable read-models.
- **Audit/outbox are shared infrastructure owned by `auditoutbox`,** written in the same transaction as
  the accepted business mutation (never independently committed).

---

## 8. Connector taxonomy (decision)

Eight **distinct** categories — one generic `Connector` interface is explicitly **rejected** because it
would erase authority, protocol, delivery, transactional, and failure differences. Full matrix in
`06_CONNECTOR_TAXONOMY.mmd`.

1. **Inbound Channel Adapters** — Telegram, WhatsApp, Email, public API, buyer portal, file upload,
   agent/SFTP, marketplace webhook. *May only emit canonical inbound envelopes* — never create Quote/
   Order/PaymentAllocation/Approval/ChangeRequest.
2. **Outbound Business Communication Adapters** — email, Telegram, WhatsApp, SMS, push, outbound webhook.
   Driven by `communicationdelivery` outbox; owns no business state.
3. **Security Delivery Adapters** — MFA email/SMS, password reset, device verification, security notice.
   **Physically separate** from category 2 (`securitydelivery`), different blast radius and audit.
4. **Identity Providers** — OIDC/OAuth2, enterprise SSO, tenant IdP, Operant staff IdP (P1-C proven for
   OIDC; staff SSO/MFA/JIT NOT_PROVEN).
5. **Business-System Connectors** — 1C, ERP, CRM, accounting, SQL views, CSV/XLSX/SFTP, REST/OData.
   Read vs write capabilities are **never conflated**; default DRAFT+READ_ONLY.
6. **AI & Document Providers** — LLM, OCR, STT, parser, malware scanner, embedding/reranking. Advisory.
7. **Payment Providers** — PSP, bank API, bank statement, payment-link. Effects via outbox + reconciliation.
8. **Connector Execution Runtime** — Connector Gateway (P1-F), operant-agent (P1-G, outbound-only),
   capability registry, credential references, checkpoints, retry, revocation, reconciliation.

---

## 9. PostgreSQL strategy (decision)

**One physical PostgreSQL platform, one authoritative Operant transactional database.** Rationale and
consequences in ADR-004.

- Explicit **schema/table ownership by bounded context** (`OPERANT_DATA_OWNERSHIP_MATRIX.tsv`,
  `10_DATA_OWNERSHIP_POSTGRES.mmd`). Today all tables share one schema; target introduces *logical*
  ownership (module-owned repositories + naming), not physical DB-per-module.
- **Tenant discriminator on every business row**; tenant-scoped repository methods and tenant-aware
  constraints/indexes are mandatory (already the norm — `data-authority-model.md`).
- **Module-owned repositories; no arbitrary cross-module table access.**
- One transaction may span several modules **only** when one critical invariant requires it (e.g.
  accept-quote writing Quote + Audit + Outbox atomically).
- **Audit + outbox rows commit atomically** with the accepted business mutation.
- Future DB-extraction seams are *documented* (View 15), not implemented.
- **Rejected:** DB-per-small-module; one instance per service group without measured need;
  cross-database transactions; Saga where one local transaction suffices.

---

## 10. Redis strategy (decision)

**Not partitioned by business module.** Partitioned by **operational semantics** (ADR-005,
`11_REDIS_RUNTIME_MODEL.mmd`). Grounded in current usage:

| Operational role | Current code | Truth source of record |
|---|---|---|
| Session + revocation | BFF `REDIS_REQUIRED` (execution-state), gateway replay admission `RedisGatewayHeaderReplayAdmissionStore` | Postgres identity; Redis is ephemeral |
| Replay admission (gateway + control namespace) | `GatewayHeaderReplayAdmissionStore`, `RedisGatewayHeaderReplayStoreConfiguration` | Postgres/audit |
| Rate limiting / runtime admission | `RedisRateCounter`, `LettuceRedisRateCounter`, `RuntimeRateRedisConfiguration` | n/a (ephemeral) |
| Short-lived cache / provider circuit state | (target) | owning module |
| Control dependency probe | `ControlPlaneStatusService` | n/a |

**Redis must never be sole source of truth** for tenant membership, quote, order, payment, inventory
authority, approval, audit, entitlement, or durable job ownership. Separate Redis deployments proposed
**only** where availability/eviction/persistence/security/traffic/blast-radius/scaling differ — namely a
**session/security Redis** vs a **runtime/rate/cache Redis** (two logical roles; physical split is
justified but **NEEDS_OWNER_DECISION** on ops budget).

---

## 11. Canonical flows

Sixteen canonical flows are catalogued with full attributes (actors, entry adapter, canonical request,
backend-resolved authority, orchestrator, authoritative module, legal transition, transaction boundary,
idempotency identity, concurrency, audit, outbox, async work, external effect, partial-failure,
reconciliation, safe response, performance path, current duplicates, migration plan) in
`OPERANT_FLOW_CATALOG.tsv`. Diagram views 03–09 render the primary ones. Summary:

1. Telegram/email/API → Inquiry/RFQ (View 03/04)
2. File upload → quarantine → scan → parse/OCR → evidence → Inquiry (View 05)
3. Inquiry → customer/product resolution → validation → WorkCase (View 04)
4. Operator correction → full deterministic revalidation (View 04)
5. Inquiry/RFQ → Draft Quote (View 04)
6. Quote approval → immutable Quote Artifact (View 04/07)
7. Quote delivery → Communication Outbox → provider (View 09)
8. External buyer accept/reject/request-change (View 07)
9. Accepted Quote → Order (View 07)
10. Order → approved ChangeRequest → connector execution (View 07)
11. Connector import → staging → validation → activation (View 05/07)
12. Payment event → matching → atomic allocation → reconciliation (View 08)
13. MFA/security challenge → Security Delivery (View 09)
14. AI advisory request → typed result → deterministic validation (View 04)
15. Operant staff support / JIT diagnostic access (View 13)
16. Tenant onboarding/configuration/dry-run/UAT/activation/rollback (View 01/13)

---

## 12. State-machine catalogue

Eighteen critical aggregates catalogued in `OPERANT_STATE_MACHINE_CATALOG.tsv` with per-transition
attributes (source, target, actor/plane, authority, preconditions, permission, scope, lock strategy,
idempotency, audit, outbox, forbidden transitions, terminal states). Focused diagrams in
`state-machines/`. Aggregates: Inquiry, Document, Import, WorkCase, Quote, QuoteArtifact, Order,
Fulfillment, Invoice/AR mirror, PaymentEvent, PaymentObligation, PaymentAllocation, ChangeRequest,
ConnectorExecution, DeliveryAttempt, SupportAccessGrant, BreakGlassGrant, LifecycleOperation/BackupArtifact.

Grounded state sources (real enums/columns at HEAD): `PaymentObligationStatus`,
`PaymentAllocationStatus`, `ConnectorRunStatus`, `ConnectorCommandExecutionState`,
`CompensationPlanStatus`, `ProcessingJobStatus`, `ValidationCaseStatus`, `IncidentStatus`,
`BreakGlassStatus`, `AlertStatus`, `LifecycleOperationState`, `BackupArtifactState`, `MilestoneState`,
`ReconciliationStatus`, `TrustApprovalStatus`, `TrustRiskDecisionStatus`. **Quote/Order lifecycle is
string-valued** (`DRAFT, DRAFT_ASSEMBLED, PENDING_APPROVAL, WAITING_APPROVAL, APPROVED,
APPROVED_INTERNAL, REJECTED, CANCELLED, EXPIRED`) with no DB CHECK — flagged **CURRENT_PARTIAL** and a
priority hardening target (ADR-010).

---

## 13. Transaction / consistency policy

Per-mutation model in `OPERANT_FLOW_CATALOG.tsv`. Mechanism ladder (smallest sufficient first, ADR-009/010):

1. DB constraint / atomic conditional update
2. optimistic version (`@Version`)
3. pessimistic row lock (`@Lock(PESSIMISTIC_WRITE)` — already used, e.g. channel-identity, quote assemble)
4. compare-and-set
5. serialized queue owner (per ordering key)
6. lease with fencing (already present: `WorkerJobLeaseService`, control executor lease)
7. Saga **only** for genuinely distributed ownership (none required inside the monolith today)

- **Audit + outbox atomic with business mutation.** Accepted mutation, its `AuditEvent`, and any
  `OutboxEvent` commit in one local transaction. A rolled-back business change must roll back its audit.
- **External effects are separated** from the business transaction: the outbox row is written in-tx; a
  separate relay publishes (at-least-once) — currently `PUBLISHED_INTERNAL_ONLY`/`SKIPPED_EXTERNAL_DISABLED`.
- **No exactly-once external processing is claimed.** Model = at-least-once delivery + idempotent effect
  (idempotency key + `ConnectorIdempotencyService`), or a stronger proven local invariant.
- Ordering keys use only what the invariant needs: `tenant+inquiryId`, `tenant+quoteId`, `tenant+orderId`,
  `tenant+paymentObligationId`, `tenant+connectionId`.

---

## 14. Audit / outbox policy

- `auditoutbox` owns `AuditEvent` (append-only, no public mutation API), `OutboxEvent`, and the
  idempotency records. Other modules append via the audit/outbox service inside their own transaction.
- Outbox status lifecycle is the external-effect state machine: `PENDING → PUBLISHED_INTERNAL_ONLY |
  SKIPPED_EXTERNAL_DISABLED` today; State-2 adds `PUBLISHED_EXTERNAL`/`FAILED`+retry when P1-F relay lands.
- **Accepted audit can never commit independently of a rolled-back business state** (quality gate).

---

## 15. Security / access-plane policy

Four independent planes, already distinguishable in `ApiRouteSecurityPolicy` (`13_ACCESS_PLANES_TRUST_BOUNDARIES.mmd`):

| Plane | Credential | Route evidence | May never |
|---|---|---|---|
| **Tenant User** | OIDC session via BFF → server-resolved actor + `X-Tenant-Id` | tenant business permissions (`ANALYTICS_*`, `REVIEW_*`, `QUOTE_*`, `ADMIN_SETTINGS_*`, `TRUST_*`, `CHANGE_REQUEST_*`) | receive `STAFF_*` / `CONTROL_EXECUTOR_*` |
| **External Customer** | opaque expiring token in path (no tenant/actor header) | `SECURE_TRACKING_LINK_PUBLIC_WITH_TOKEN`, `/api/v1/public/order-tracking` (`ApiRouteSecurityPolicy.java:80-85`) | mutate; read anything outside token scope |
| **Service Account** | signed webhook / gateway HMAC / internal AI intake | `WEBHOOK_PUBLIC_WITH_SIGNATURE_OR_TOKEN`, `AI_RESULT_INTAKE`, gateway-signed headers | own business decisions; write business tables |
| **Operant Staff & Maintenance** | dedicated STAFF machine credential / (target) staff SSO+MFA+JIT | `STAFF_*` (control/support/incident/break-glass/data-repair) + disjoint `CONTROL_EXECUTOR_*` (`ApiRouteSecurityPolicy.java:233-399`) | act as tenant users; hold both requester and approver authority (separation of duties enforced) |

Global `/api/**` **default-deny**; every exposed route answers who-may-call, backend-resolved authority,
protecting permission, scope, legal transition, denied-no-mutation behavior, and allowed/forbidden
response fields (per-route matrix in `OPERANT_FLOW_CATALOG.tsv`).

---

## 16. Performance and capacity model

`14_HIGH_LOAD_BACKPRESSURE.mmd`, ADR-011.

- **Fast request path:** verification → tenant resolution → bounds → idempotency → minimal durable
  persistence → acknowledgement. Heavy work never runs inline.
- **Async heavy paths** (own job types/tables/pools): document parse/OCR, AI advisory, channel
  normalization, connector sync, communication delivery, analytics projection, reconciliation, malware
  scan. Backed today by `ProcessingJob` + `WorkerJobLeaseService`.
- **Workload isolation:** per-workload worker pools, per-tenant concurrency, weighted tenant fairness,
  priority classes, global resource ceilings, provider-specific limits, large-file isolation,
  backpressure, degraded mode, bounded retry + DLQ/reconciliation.
- **DB hot-path rules:** no unbounded `findAll`; indexed tenant-scoped queries; cursor pagination;
  batching; bounded projections; no N+1; blobs in object storage; DB constraints for invariants; read
  models for dashboards; explicit query/latency budgets.
- **Cache rules:** every cache declares source-of-truth, key, tenant scope, TTL, invalidation,
  stale-read tolerance, memory bound, fallback, and whether eviction can affect correctness (it must not).

---

## 17. Failure / degraded-mode model

- Provider/connector failure → circuit state in Redis + outbox retry with bounded attempts → DLQ /
  reconciliation case; business state stays consistent (external effect separated).
- Worker outage → job leases expire (fencing) and are re-leased; no double-execution because effects are
  idempotent.
- Redis outage → session/rate degrade closed (deny rather than bypass); business truth in Postgres intact.
- Control-plane probe saturation → maps dependency `DOWN`, never false-`READY` (execution-state P1-E).
- Partial payment/connector effect → reconciliation catches divergence (View 08/07).

---

## 18. Future State-3 extraction seams

`15_STATE_3_EXTRACTION_SEAMS.mmd` — **dashed** boxes only, never presented as current services. A module
qualifies as a seam only when all 12 gate criteria hold (owner, versioned contract, independent scaling,
independent failure domain, independent deployment reason, no shared ACID need, defined eventual
consistency, idempotency/duplicate behavior, reconciliation/compensation, separate data ownership,
observability/SLO, evidence extraction lowers total risk/cost). Candidate seams that *partially* qualify
today: `connectorexecution` (already fronted by the P1-F Connector Gateway boundary), `documentevidence`
+ AI/OCR worker (already a separate advisory runtime), `communicationdelivery`/`securitydelivery`
(delivery workers). None is extracted in this freeze.

---

## 19. Current-to-target migration waves

Full class/package mapping in `OPERANT_CURRENT_TO_TARGET_MIGRATION_MAP.tsv`. Waves:

- **Wave 0 — Enforcement scaffolding (no behavior change).** Add Spring Modulith package markers +
  ArchUnit rules (design in `OPERANT_DEPENDENCY_RULES.md`); make module boundaries fail CI. No moves yet.
- **Wave 1 — Namespace collapse.** Fold `/api/stage8/*`, `/api/stage9/*` onto `/api/v1/*` behind
  compatibility shims (D6). DEPRECATE legacy after FE migration.
- **Wave 2 — Quote de-god.** Split the quote controller cluster (D4) into `quote` (aggregate),
  `ordermanagement` (conversion), `workmanagement` (review UX). Add `quote_status` DB CHECK / enum.
- **Wave 3 — Intake canonicalization.** MERGE webhook + RFQ handoff controllers (D1/D2) behind the
  Canonical Intake Pipeline; typed inbound envelope.
- **Wave 4 — Trust/validation split.** Separate `aiadvisory`, `decisiontrace`, `commercialpolicy/risk`
  out of the 86-file `trust` and 43-file `validation` packages (D3).
- **Wave 5 — Delivery split.** Introduce `communicationdelivery` vs `securitydelivery` owners; wire the
  outbox relay for State-2 external effects (P1-F).
- **Wave 6 — Projection consolidation.** MERGE analytics controllers (D5) into `commerceintelligence`
  read-models.

Each SPLIT/MERGE/REPLACE/DELETE_AFTER_MIGRATION item carries a required behavioral proof and a deletion
condition (no working code is deleted before replacement behavior is proven).

---

## 20. Architecture enforcement plan

Design-only (not implemented here) — see `OPERANT_DEPENDENCY_RULES.md`:
Spring Modulith module verification; ArchUnit rules (no module cycles; no cross-module `internal`
imports; no cross-module repository imports; no cross-module entity mutation; no application→web-DTO
dependency; no domain→application dependency); route/permission parity test (extends existing
`ApiRouteSecurityClassificationTest`); tenant-ownership check; direct-entity-response prevention; DTO
authority-field checks; audit/outbox transaction rules; idempotency/concurrency rules. An architecture
test already exists to build on: `LifecycleTerminalOwnerArchitectureTest`.

---

## 21. Owner decisions required

See `OPERANT_ARCHITECTURE_DECISIONS_REQUIRED.md`. Highest priority:
1. Confirm State-1/2/3 ↔ Phase-1 mapping (missing roadmap doc) — **blocks calling this "final".**
2. `billingar` / Invoice-AR ownership: is AR an owned aggregate or an ERP mirror projection?
3. `securitydelivery` vs `communicationdelivery` physical split + MFA provider choice.
4. Redis physical split (session/security vs runtime/rate) — ops budget.
5. Legacy `/api/stage8|stage9` deprecation timeline (FE dependency).
6. Quote/Order state enforcement (DB CHECK vs JPA enum vs central allowlist).

---

## 22. Explicit NOT_PROVEN items

- Four mandatory-read documents are **absent from the repo**: `OPERANT_THREE_STATE_EXECUTION_ROADMAP_V2.md`,
  `reverse-защита-и-инструкций-по-бизнес-логике-читать-всегда-перед-промптом.txt`,
  `OPERANT_PROMPT_ENGINEERING_BUSINESS_LOGIC_ARCHITECTURE_STANDARD_V1.md`,
  `OPERANT_TOP_TIER_SECURITY_ARCHITECTURE_CONSTITUTION_V1.md`. No AGENTS.md is tracked in git either
  (`.gitignore` history shows they were once tracked then the blanket ignore re-applied; not at HEAD).
- Production runtime for P1-E (credential issuance, private management ingress, live operantctl, staff
  SSO/MFA/JIT, persistent control-access audit, clean-host runtime) — **NOT_PROVEN** (execution-state).
- External connector writes, outbox external publish, real payment matching, MFA delivery — **NOT_PROVEN**
  (all disabled/stub today).
- Independent external-customer / service-account / staff authentication flows — **NOT_PROVEN**.
- State-machine transition details for string-valued Quote/Order are inferred from services, not a
  declared enum — **CURRENT_PARTIAL**.

---

## 23. Final architecture verdict

**CHANGES_REQUIRED** — the target architecture is code-grounded, internally consistent, enforceable, and
State-2-compatible, and is ready to guide implementation. It is **not** declared the *final* freeze
because a small set of **owner decisions** (§21) — chiefly the missing three-state roadmap that defines
"State 2", plus billing/AR and security-delivery ownership — must be confirmed before "final". Nothing
here requires unrestricted SQL, arbitrary shell, hidden backdoor, direct AI/connector business mutation,
client-owned authority, or premature distributed transactions.

> Verdict of the deliverable as a whole is stated in `OPERANT_ARCHITECTURE_VALIDATION_REPORT.md`.
