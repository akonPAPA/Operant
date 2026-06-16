# OP-CAP-17B — Counterparty Trust Profile Foundation

## 1. Purpose

Build the first tenant-scoped **counterparty trust profile** for OrderPilot's Transaction Trust
Intelligence layer. OP-CAP-17A detects per-document risk signals; 17B accumulates those signals (and
future payment/order behaviour) into a durable, explainable trust profile per customer account, so
operators can see whether a counterparty is trustworthy over time — not just whether one document
looks risky.

This is a business signal for operator review. It is **not** a legal or fraud verdict, and no AI
output is authoritative: AI suggests, rules validate, humans approve risky cases, the backend writes,
audit records.

## 2. Scope and non-scope

In scope (this stage):
- Counterparty trust profile domain model (profile, snapshot, signal).
- Deterministic, AI-free trust scoring + tier derivation.
- Counterparty-level signal/counter aggregation.
- Integration hook so OP-CAP-17A document trust runs feed the profile when a counterparty is known.
- Read-only, tenant-scoped trust profile API.
- Tests: scoring, isolation, risk routing, idempotency, sensitive-data boundaries, 17A integration.

Non-scope (later stages):
- Payment obligation intelligence, settlement/statement import, real PSP/bank integration → 17C.
- Full analytics dashboard → 17E.
- AI memory/cache governance → 17F.
- Autonomous fraud verdict; external ERP/1C writes.
- Bank account history table (deferred — see §10; `bankAccountChangeCount` is kept on the profile).
- Frontend trust panel (deferred — see §13).

## 3. Domain model

Package `com.orderpilot.domain.trust` (extends the 17A trust package).

- **CounterpartyTrustProfile** — current trust state, unique per `(tenant_id, customer_account_id)`.
  Scores (`trustScore`, `documentReliabilityScore`, `paymentReliabilityScore`, `orderPatternScore`),
  `trustTier`, last decision snapshot (`lastDocumentTrustRunId`, `lastRiskLevel`, `lastTrustSignalAt`),
  and behaviour counters. All business counters are `long` (BIGINT).
- **CounterpartyTrustSnapshot** — append-only evidence row written whenever a decision updates the
  profile. Bounded `reasonSummary`; never mutated.
- **CounterpartyTrustSignal** — counterparty-level signal (`CounterpartySignalCode`, severity reuses
  17A `TrustRiskLevel`, bounded `explanation`, `sourceType`, `sourceRefId`).

Enums: `TrustTier` (TRUSTED/STABLE/WATCHLIST/HIGH_RISK/UNKNOWN), `CounterpartySignalCode`,
`CounterpartyTrustSourceType` (DOCUMENT_TRUST_RUN, MANUAL_OVERRIDE, PAYMENT_SIGNAL, ORDER_HISTORY,
IMPORTED_BASELINE, SYSTEM_RECALC). Severity reuses the 17A `TrustRiskLevel` scale.

Counterparty id = existing `CustomerAccount` UUID (`customer_account_id`). No new account system was
introduced.

## 4. Scoring model

Deterministic and cheap (`CounterpartyTrustScoringService`), computed only from profile counters — no
historical scan, no AI.

- Base: `70` for a profile with activity, `50` for a new/unknown profile.
- Penalties (per occurrence): high-risk doc −8, critical doc −20, manual review −3, rejected doc −6,
  dispute −10, bank-account change −5, overdue payment −7 (placeholder).
- Reward: bounded positive history, `min(completedOrderCount, 20)`.
- `trustScore = clamp(base − penalties + reward, 0, 100)`.
- Component scores (`documentReliabilityScore`, `paymentReliabilityScore`, `orderPatternScore`) are
  derived from their relevant counters and clamped 0..100; neutral `50` when no data.

**Overflow safety:** every counter is capped (`min(count, 1_000_000)`) before weighting, so arbitrarily
large `long` counters cannot overflow the arithmetic; the result is always clamped to 0..100.

## 5. Risk / tier mapping

From `trustScore`: 85–100 TRUSTED, 70–84 STABLE, 50–69 WATCHLIST, 0–49 HIGH_RISK; UNKNOWN when the
profile has no activity yet.

**Critical evidence is never hidden by historical trust.** After scoring, a tier floor is applied
from `lastRiskLevel`:
- last risk CRITICAL → tier forced to at least HIGH_RISK;
- last risk HIGH → tier forced to at least WATCHLIST.

So a single critical/high document on an otherwise high-scoring counterparty still surfaces as
HIGH_RISK/WATCHLIST. Low/medium risk only increments counters and continues (no blocking). High and
critical are surfaced via `lastRiskLevel` + tier as approval/blocking signals, consistent with the
17A risk-decision pattern. Manual override (future) may allow progression but never deletes signal
evidence (snapshots/signals are append-only).

## 6. Stage 17A integration

`DocumentTrustService.evaluate(tenantId, sourceDocumentId, validationRunId, customerAccountId, candidate)`
(additive overload; the original 4-arg method delegates with a null counterparty) calls
`CounterpartyTrustProfileService.applyDocumentTrustResult(...)` after a **new** run is created:
- increments `totalDocumentCount`, and high/critical/warning counters by run risk level;
- HIGH → `DOCUMENT_HIGH_RISK_SIGNAL`; CRITICAL → `DOCUMENT_CRITICAL_RISK_SIGNAL`;
- a 17A `BANK_ACCOUNT_HOLDER_MISMATCH` document signal → counterparty `BANK_ACCOUNT_HOLDER_MISMATCH`
  signal + `bankAccountChangeCount++` (count/metadata only, no bank data);
- updates `lastDocumentTrustRunId` / `lastRiskLevel`, recomputes scores, appends a snapshot;
- emits audit events (§ Audit).

Safety: if no counterparty is known, **no global/unscoped profile is created** — the update is
skipped. Idempotent on the run id (a snapshot keyed `(tenant, counterparty, DOCUMENT_TRUST_RUN, runId)`
guards against double counting). The profile update runs in the same bounded transaction as the
document trust run; the original 17A evaluation and tests are unchanged.

## 7. API endpoints (read-only)

Tenant resolved from context; the path counterparty id is only ever looked up tenant-scoped.

- `GET /api/v1/trust/counterparties/{counterpartyId}` → full profile view (scores, tier,
  `lastRiskLevel`, counts, recent signals + snapshots, default 25 each).
- `GET /api/v1/trust/counterparties/{counterpartyId}/signals?limit=25` → recent signals.
- `GET /api/v1/trust/counterparties/{counterpartyId}/snapshots?limit=25` → recent snapshots.

Limit defaults to 25 and is clamped to a max of 100. A counterparty with no profile returns
`404 NOT_FOUND` (consistent with 17A; reads never create profiles).

## 8. Security boundaries

- Guarded by the existing `/api/v1/trust` → `TRUST_READ` permission prefix (reused; no new permission
  needed). Missing permission → `403 TENANT_POLICY_DENIED`.
- All mutations go through `CounterpartyTrustProfileService` — never AI/bot/frontend/connector/webhook.
- Tenant id is never accepted from the request body; it is resolved from `TenantContext`.
- The API exposes only bounded scores, counts, and generic explanations. It never exposes bank
  fingerprints/hashes, account numbers, IBAN/routing data, raw evidence, or internal notes.

## 9. Tenant isolation rules

- Every table carries `tenant_id`; every repository query is tenant-scoped.
- Profile unique key is `(tenant_id, customer_account_id)` — the same counterparty in two tenants
  yields two isolated profiles.
- Cross-tenant reads return 404; tests prove tenant A cannot read tenant B's profile/signals/snapshots.

## 10. Sensitive data policy

- No raw document text, prompt text, or full document payloads.
- No PAN/CVV/magnetic-stripe/raw NFC data; no raw bank credentials, account numbers, IBAN, or routing
  numbers; no bank statement payloads.
- Bank stability is represented as a **count** (`bankAccountChangeCount`) only. The optional
  `CounterpartyBankAccountHistory` (fingerprint hash / masked label) is **deferred** to avoid scope
  bloat; when added it will store only hashes/masked labels, never raw banking data.
- Money/counters use `long` / `BigDecimal`.

## 11. Performance / indexing strategy

- Recompute is O(1) over profile counters — no per-update historical signal scan.
- "Recent" reads are bounded via `Pageable` (default 25, max 100).
- Indexes (migration `V45`): profile `(tenant_id, customer_account_id)` unique,
  `(tenant_id, trust_tier)`, `(tenant_id, updated_at DESC)`; snapshot
  `(tenant_id, customer_account_id, created_at DESC)` and `(tenant_id, customer_account_id, source_type,
  source_ref_id)` (idempotency); signal `(tenant_id, customer_account_id, created_at DESC)`,
  `(tenant_id, signal_code, created_at DESC)`, `(tenant_id, severity, created_at DESC)`.
- Read DTOs only; no heavy joins; entities are never returned directly.

## 12. Tests added

Backend service/integration (`CounterpartyTrustProfileServiceStage17BTest`, 17):
new-profile baseline; high-risk lowers score + snapshot; **critical not hidden by positive history**;
low/medium counters without blocking; score clamp floor; large-counter no-overflow; run-idempotency;
unknown counterparty → no global profile; unique per tenant+counterparty; two-tenant isolation;
bounded + tenant-scoped recent signals/snapshots; cross-tenant read 404; limit clamp; 17A integration
(known counterparty updates profile; no counterparty → no profile; document signal code mapping).

Backend API/security (`CounterpartyTrustControllerStage17BTest`, 4): authorized read; no sensitive
fields in response; unknown profile 404; limit forwarded for clamping.

Permission (`ApiPermissionInterceptorPermissionTest`, +2): counterparty trust read requires
`TRUST_READ`.

Commands: `mvn -o -Dtest=CounterpartyTrustProfileServiceStage17BTest,CounterpartyTrustControllerStage17BTest,DocumentTrustFoundationStage17ATest,DocumentTrustControllerStage17ATest,ApiPermissionInterceptorPermissionTest test` → all green; 17A tests unchanged.

## 13. Known limitations

- **Frontend deferred.** No trust panel added this stage; the validation-review detail UI has no
  reliable counterparty-id binding yet, so a panel would risk a fake/ungrounded badge. The read API is
  ready for a later UI slice.
- Payment/order signal producers are placeholders; `paymentReliabilityScore` and order counters are
  neutral until 17C/17D.
- Bank account history table deferred (counter only).
- No outbox events: the 17A/trust module does not use the outbox, so none was added; audit events are
  emitted instead. A service seam exists for adding `CounterpartyTrustProfileUpdated` /
  `CounterpartyHighRiskSignalRecorded` events later without refactor.
- Scoring weights are a deterministic heuristic, tunable later without schema change.

## 14. Next stage recommendation

**OP-CAP-17C — Payment Obligation Intelligence**: populate the payment placeholders (overdue/partial/
late counters, `paymentReliabilityScore`, `lastPaymentAt`) from internal obligation state — still no
real PSP/bank integration, still operator-review oriented, still tenant-scoped and audited.
```
