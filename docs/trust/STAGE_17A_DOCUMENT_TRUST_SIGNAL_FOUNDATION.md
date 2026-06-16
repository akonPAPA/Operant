# OP-CAP-17A — Document Trust Signal Foundation

## Scope

The first deterministic Transaction Trust Intelligence layer for OrderPilot. It evaluates whether an
inbound document carries suspicious business signals **before** OrderPilot allows irreversible
commercial actions, and routes high/critical cases to human review.

This stage delivers a backend-only foundation:

- Trust domain (`com.orderpilot.domain.trust`): enums, entities, value objects, repositories.
- `DocumentFingerprintService` — tenant-scoped content-hash fingerprinting and duplicate detection.
- `DocumentTrustService` — deterministic, **idempotent** trust evaluation producing a run + signals
  + decision.
- `DocumentTrustDecisionPolicy` — deterministic signal → risk-level + clamped (0..100) risk score →
  routing mapping.
- Persistence: `document_fingerprint`, `document_trust_run`, `document_trust_signal` (Flyway `V44`).
- Narrow read-only API: `GET /api/v1/trust/document-runs/{id}` (permission `TRUST_READ`).

### Idempotency

A trust run is keyed for idempotency so repeats do not create duplicate **active** records:

- If the caller supplies an `idempotencyKey`, dedup is `(tenant_id, idempotency_key)` among active
  runs.
- Otherwise dedup is the natural key `(tenant_id, source_document_id, content_sha256)` among active
  runs with no idempotency key.

The service short-circuits to the existing active run before fingerprinting or signal creation.
Partial unique indexes (`ux_document_trust_run_idem_key`, `ux_document_trust_run_idem_natural`) act
as a Postgres backstop.

### Numeric safety

- `riskScore` is clamped to `0..100` in the domain (`DocumentTrustDecision.clampScore`) and enforced
  in the DB (`chk_document_trust_run_risk_score`).
- Sizes/counters use `long`/`BIGINT` (`file_size_bytes`, `content_byte_size`) and `INTEGER`
  (`page_count`, `signal_count`); money inputs are `BigDecimal` at the service layer (not stored).

### Evidence

Every signal carries bounded evidence metadata: `fieldKey` (≤64), `pageNumber` (nullable),
`evidenceRef` (≤120, a metadata reference pointer), and `explanation` (≤280, generic text). No raw
document text or large OCR text is ever stored.

### Source linking

`document_trust_run` links the existing `source_document_id` (plain UUID reference, matching existing
inbound/source-document referencing conventions) and an optional `validation_run_id`. No new document
domain is introduced.

### Audit

HIGH and CRITICAL trust decisions emit a `DOCUMENT_TRUST_DECISION_RECORDED` audit event (bounded
metadata: risk level/score, decision state, routing flags, counts — no raw text). LOW/MEDIUM
decisions are not audited (no new audit framework added; reuses `AuditEvent`).

## Non-scope

- **Not** a fraud verdict or legal determination system (see below).
- No vision/OCR model integration (OCR confidence is supplied as bounded metadata input).
- No external API calls, no payment-provider integration, no ERP/1C connector writes.
- No AI-to-business-table writes; no frontend-to-DB; no connector writes.
- No write/create/list HTTP endpoints — trust runs are produced by backend services only.
- No frontend in this stage.
- No counterparty profile / historical trust accumulation (deferred to OP-CAP-17B).

## Risk signal taxonomy

Severity scale: `INFO` < `WARNING` < `HIGH` < `CRITICAL`. Only `WARNING` and above are *material*.

| Signal code (`TrustSignalCode`)        | Severity | Deterministic rule |
|----------------------------------------|----------|--------------------|
| `DOCUMENT_DATE_IN_FUTURE`              | WARNING  | document date is after evaluation time |
| `DUE_DATE_BEFORE_ISSUE_DATE`          | WARNING  | due date precedes issue date |
| `DUPLICATE_DOCUMENT_HASH`             | HIGH     | identical content hash already seen **for the same tenant** |
| `BANK_ACCOUNT_HOLDER_MISMATCH`        | HIGH     | bank account holder ≠ expected counterparty (case/space-insensitive) |
| `OCR_CONFIDENCE_LOW_CRITICAL_FIELD`   | HIGH     | OCR confidence on a critical field ≤ `0.60` |
| `DOCUMENT_TOTAL_MATH_MISMATCH`        | HIGH     | declared total ≠ computed line-item total |

All signal `detail` text is a fixed, generic explanation. It never contains raw document text,
extracted values, or counterparty identity values.

## Risk routing

`DocumentTrustDecisionPolicy` takes the **maximum** risk implied by any signal — no model output and
no historical "trust discount" can lower it.

| Risk level (`TrustRiskLevel`) | Trigger | Decision state | `requiresHumanReview` | `blocksAutomation` |
|-------------------------------|---------|----------------|-----------------------|--------------------|
| `LOW`      | no material signals          | `CONTINUE_WITH_WARNING` | false | false |
| `MEDIUM`   | at least one `WARNING`       | `CONTINUE_WITH_WARNING` | false | false |
| `HIGH`     | at least one `HIGH` signal   | `REQUIRES_REVIEW`       | true  | false |
| `CRITICAL` | a `CRITICAL` signal **or** a forced-critical combination | `BLOCK_AUTOMATION` | true | true |

Forced-critical combination (this stage): `DUPLICATE_DOCUMENT_HASH` **and**
`BANK_ACCOUNT_HOLDER_MISMATCH` together (duplicate submission + payment-holder redirection pattern).

This preserves the platform safety model: low/medium continue with a warning; high/critical require
human review/approval before any irreversible commercial action.

## Data retention

- `document_fingerprint` stores only a hex SHA-256 of a caller-provided canonical metadata/hash input
  plus an optional byte size. The raw canonical input is **never** stored.
- `document_trust_run` stores the bounded decision (risk level, 0..100 score, routing flags, counts),
  bounded size metadata (`file_size_bytes`, `page_count`), idempotency fields, and id links.
- `document_trust_signal` stores a closed-set code, severity, and bounded evidence metadata
  (`field_key` ≤64, `page_number`, `evidence_ref` ≤120, `explanation` ≤280).
- No raw document text, no full document payloads, and no PII/identity values are persisted in any
  trust table. Counterparty identity values are compared in memory only.
- Counters/sizes use `BIGINT`/`long`; money fields are handled as `BigDecimal` at the service layer.

## Tenant isolation

- Every trust table includes `tenant_id` and every query is tenant-scoped (`findBy...AndTenantId`,
  `findByTenantIdAnd...`).
- Duplicate detection is keyed on `(tenant_id, content_sha256)`; an identical document hash in a
  different tenant never matches and never leaks.
- The read endpoint resolves the tenant from `TenantContext` and loads via `findByIdAndTenantId`;
  cross-tenant reads return `404 NOT_FOUND`.
- The read endpoint requires the `TRUST_READ` API permission.
- Indexes: `document_fingerprint(tenant_id, content_sha256)`,
  `document_trust_run(tenant_id, source_document_id)`,
  `document_trust_run(tenant_id, risk_level, created_at)`,
  `document_trust_signal(tenant_id, trust_run_id)`,
  `document_trust_signal(tenant_id, signal_code, created_at)`.

## Why this is not a legal fraud verdict system

Trust signals are deterministic **risk indicators** for operator review, not assertions of fact.
OrderPilot does not claim that any document is fake, forged, or fraudulent. A signal means "this
business attribute is inconsistent or worth a human looking at it," not "this is fraud." Final
judgement and any irreversible commercial action remain with a human approver, consistent with the
platform safety model (AI suggests, rules validate, human approves if risky, backend writes, audit
records). The layer produces explainable, reproducible signals so an operator can decide — it never
substitutes for that decision.

## Next stage

**OP-CAP-17B — Counterparty Trust Profile**: tenant-scoped counterparty trust history and profile
aggregation that builds on these per-document trust runs (still operator-review oriented; critical
signals must never be masked by accumulated profile trust).
