# OP-CAP-17F — AI Data Runtime / Tenant-Scoped AI Memory Governance

## 1. Objective

Build the storage + governance primitives for a safe, tenant-scoped, resource-controlled AI runtime
memory/index layer that stores reusable, bounded, sanitized AI/runtime knowledge derived from approved
OrderPilot workflow events — plus safe AI runtime trace metadata. This stage delivers governance and
storage only: tenant-scoped memory records, namespaces, evidence references, TTL/expiration, versioning,
invalidation, usage-tracking hooks, safe retrieval, prompt/model/version metadata governance, strict
anti-cross-tenant rules, deterministic fallback when memory is absent, and audit-friendly metadata.

## 2. What "own AI system" means — and does NOT mean here

**OrderPilot AI** = provider-agnostic AI runtime + deterministic validation/risk engines + domain
extraction schemas + trust/payment/counterparty intelligence + tenant-scoped AI memory/cache +
evaluation harness + learning loop from operator corrections + optional fine-tuned/domain adapters later.

**This stage builds governance + storage primitives only.** It is explicitly NOT:
- training our own model / fine-tuning;
- a global or cross-tenant AI memory;
- raw prompt/response/document storage;
- an external AI provider integration (no provider is called);
- autonomous AI writes to business data;
- a vector DB (none is introduced — no local vector abstraction exists to reuse).

## 3. Memory namespaces

`AiMemoryNamespace` (tenant-scoped, never global): `DOCUMENT_TEMPLATE`, `PRODUCT_ALIAS_HINT`,
`COUNTERPARTY_PATTERN`, `EXTRACTION_CORRECTION`, `VALIDATION_EXPLANATION`, `TRUST_SIGNAL_HINT`,
`PAYMENT_MATCH_HINT`, `BOT_CONVERSATION_SUMMARY`, `OPERATOR_CORRECTION_PATTERN`.

## 4. Data stored vs forbidden data

**Stored (bounded, typed, sanitized):** namespace, `memoryKey`, `memoryType`, `status`, `authorityLevel`,
`sourceType` + optional `sourceId`/`sourceRef` (pointer to an existing tenant record), bounded
`title`(≤160)/`summary`(≤512)/`normalizedValue`(≤256), `confidence` (0..1), `weight`, `version`,
`expiresAt`, invalidation metadata, access counters. Evidence rows are bounded pointers
(type + ref + optional source id/field key). Runtime traces store provider/model/prompt-version/token &
cost estimates + outcome status + bounded source pointer.

**Forbidden (never stored):** raw documents, raw OCR text, raw prompts, raw model responses, raw customer
messages, secrets/API keys, card data, bank credentials, full PII dumps, unrestricted business payloads.
All text columns are bounded VARCHAR — there is no unbounded TEXT column anywhere in this layer, and the
runtime trace entity deliberately has **no** prompt-body / response-body / raw-message column.

`AiMemoryPolicyService.validateMemoryPayload(...)` runs **before persistence** and rejects: over-length
bounded fields, and obvious raw-prompt/secret markers (`BEGIN SYSTEM PROMPT`, `OPENAI_API_KEY`,
`Authorization:`, `password=`, `secret=`, `-----BEGIN PRIVATE KEY-----`, `Bearer `). This is a
conservative guard, not a full DLP engine.

## 5. Authority model

- `AiMemoryAuthorityLevel`: `LOW`, `MEDIUM`, `HIGH`, `HUMAN_APPROVED`, `SYSTEM_DERIVED`.
- Memory is **advisory and low-authority**. It is NEVER the source of truth for orders, quotes, prices,
  stock, payments, counterparty trust, or approval status — `isAuthoritativeUseAllowed(...)` is always
  `false` by design. Deterministic backend services always win.
- `HUMAN_APPROVED` / `SYSTEM_DERIVED` may rank higher and bypass the low-confidence floor, but they are
  still not authoritative.
- `shouldPreferDeterministicSource(namespace)` is `true` for the high-stakes namespaces
  (`PRODUCT_ALIAS_HINT`, `COUNTERPARTY_PATTERN`, `PAYMENT_MATCH_HINT`, `TRUST_SIGNAL_HINT`): callers must
  defer to the deterministic backend and use memory only as a hint.
- **Deterministic fallback when memory is absent:** retrieval returns an empty result (never a fabricated
  one); `AiRuntimeStatus.FALLBACK_USED` records that the deterministic path was taken.

## 6. Tenant isolation rules

- Every row (`ai_memory_record`, `ai_memory_evidence_ref`, `ai_memory_invalidation_event`,
  `ai_runtime_trace`) carries `tenant_id` (NOT NULL, FK to `tenant`).
- Every repository finder and every cache/search key is tenant-scoped; there is no global/cross-tenant
  search and no shared unscoped fingerprint lookup.
- Services resolve the tenant from `TenantContext`; path/body ids are never trusted across tenants
  (tenant-scoped load throws `NotFound` for another tenant's id — test:
  `tenantCannotReadOrInvalidateAnotherTenantsRecord`).
- Retrieval does not bypass the permission interceptor (RBAC/tenant policy).

## 7. TTL / version / invalidation behaviour

- **TTL:** `ttlSeconds` (optional, positive) → `expiresAt = now + ttl`. Search excludes past-TTL records
  by default; `includeExpired=true` returns ACTIVE records past their TTL.
  `expireDueRecords(tenantId, now)` sweeps ACTIVE past-TTL records to `EXPIRED` (bounded scan, cap 200).
- **Versioning:** unique `(tenant_id, namespace, memory_key, version)`; at most one `ACTIVE` version per
  key. `createMemoryRecord` rejects a duplicate active key (`Conflict`) — supersede instead.
  `supersedeMemoryRecord` marks the current version `SUPERSEDED` (+ invalidation event
  `SUPERSEDED_BY_NEW_VERSION`) and inserts a new `ACTIVE` version `n+1`.
- **Invalidation:** `invalidateMemoryRecord(...)` sets `INVALIDATED` (idempotent), preserves the record,
  and appends an `AiMemoryInvalidationEvent` (previous/new status, reason code, reason, actor). Reason
  codes: `USER_INVALIDATED`, `SOURCE_UPDATED`, `CONFLICTING_EVIDENCE`, `EXPIRED`,
  `SUPERSEDED_BY_NEW_VERSION`, `LOW_CONFIDENCE`, `POLICY_CHANGE`, `TENANT_PURGE`.
- **Usage tracking:** `recordMemoryAccess(...)` bumps `accessCount` + `lastAccessedAt` only.
- **Low confidence:** records below `0.50` are stored but excluded from search unless
  `includeLowConfidence=true`.
- Create/supersede/invalidate emit an `AuditEvent` (ids + bounded metadata only).

## 8. Runtime trace behaviour

`AiRuntimeTrace` stores safe metadata for one AI/runtime workload: `workloadType`, optional
`modelProvider`/`modelName`, `promptVersion`, `schemaVersion`, token & cost estimates, `AiRuntimeStatus`
(`SUCCEEDED`/`FAILED`/`REJECTED`/`SKIPPED`/`FALLBACK_USED`), `failureCode`, and an optional bounded source
pointer. It records governance metadata only — no raw prompt/response/secret/message text (the entity has
no such column; test asserts this via reflection). Provider-agnostic: no external provider is called.

## 9. Permission model

| Action | Path | Permission |
|---|---|---|
| Read memory / evidence / invalidations | `GET /api/v1/trust/ai-memory**` | `TRUST_AI_MEMORY_READ` |
| Create / supersede | `POST /api/v1/trust/ai-memory`, `.../{id}/supersede` | `TRUST_AI_MEMORY_WRITE` |
| Invalidate | `POST /api/v1/trust/ai-memory/{id}/invalidate` | `TRUST_AI_MEMORY_INVALIDATE` |
| Record runtime trace | `POST /api/v1/trust/ai-runtime/traces` | `TRUST_AI_RUNTIME_TRACE_WRITE` |
| Read runtime traces | `GET /api/v1/trust/ai-runtime/traces**` | `TRUST_AI_RUNTIME_TRACE_READ` |

Generic `TRUST_READ` grants none of these; write does not imply invalidate; memory write does not imply
runtime-trace write (verified in `ApiPermissionInterceptorPermissionTest`). The `/ai-memory` and
`/ai-runtime` rules are evaluated before the generic `/api/v1/trust → TRUST_READ` prefix mapping.

## 10. Performance / index notes

- Migration `V49__ai_data_runtime_memory_governance.sql` (next after V48).
- `ai_memory_record`: unique `(tenant, namespace, memory_key, version)`; indexes
  `(tenant, namespace, status, updated_at DESC)`, `(tenant, namespace, memory_key)`, `(tenant, expires_at)`.
- `ai_memory_evidence_ref`: `(tenant, ai_memory_record_id)`.
- `ai_memory_invalidation_event`: `(tenant, ai_memory_record_id, created_at DESC)`.
- `ai_runtime_trace`: `(tenant, workload_type, created_at DESC)`, `(tenant, status, created_at DESC)`,
  `(tenant, source_type, source_id)`.
- All reads are tenant-scoped, limit-clamped (default 25 / max 100) and bounded; the TTL sweep is capped.
- CHECK constraints: confidence ∈ [0,1], weight ≥ 0, version ≥ 1, access_count ≥ 0, token/cost ≥ 0.

## 11. Limitations

- No projector/outbox runtime: memory is created via the manual governance API only. **Future hook
  (documented):** when an outbox/projector exists, approved 17A–17E events (document trust run,
  trust risk decision, counterparty profile, payment obligation, operator correction) would call
  `createMemoryRecord(...)` asynchronously by `sourceType`/`sourceId`. No automatic global memory
  creation from all trust events is wired in this stage.
- No semantic/vector retrieval — exact tenant + namespace + key lookup and bounded ranked search only
  (no vector DB abstraction exists to reuse).
- `purgeTenantMemoryByNamespace` is intentionally omitted (no admin purge endpoint pattern in scope);
  `TENANT_PURGE` reason code is reserved for a future admin flow.
- Sanitization is a conservative marker/length guard, not a full DLP/entropy engine.
- No external AI provider, no model training/fine-tuning, no frontend, no Kafka/microservices.

## 12. Tests run

- **Service** (`AiMemoryGovernanceServiceStage17FTest`, 13): create with tenant scope; duplicate-active
  key rejected; active search same-tenant only; TTL exclude-by-default + `includeExpired`; low-confidence
  exclude-by-default + `includeLowConfidence`; invalidation status + event + drop-from-active; access
  counter increment; supersede new version + previous `SUPERSEDED` + event; raw-prompt/secret payload
  rejected; policy advisory/deterministic-preference + `canUseMemory` gating; runtime trace metadata-only
  (reflection asserts no raw field); tenant isolation; TTL sweep via `expireDueRecords`.
- **Controller** (`TrustAiMemoryControllerStage17FTest`, 6): create/search/get/invalidate delegation +
  runtime trace write + paged tenant-scoped list.
- **Permissions** (`ApiPermissionInterceptorPermissionTest`, +10): read/write/invalidate distinct;
  `TRUST_READ` insufficient for write; write insufficient for invalidate; runtime-trace read/write
  dedicated; memory-write insufficient for trace-write.
- **Regression:** 17A/17B/17C/17D/17E service + controller suites stay green (74 tests).
- `mvn -o compile` and the targeted `mvn -o ... test` runs: BUILD SUCCESS.

## 13. Next stage recommendation

**OP-CAP-18** (next development line) — e.g. the projector/outbox runtime that auto-populates AI memory
from approved 17A–17E events, an evaluation harness, and the operator-correction learning loop. Not
started here (Stage 18 is explicitly out of scope for 17F).
