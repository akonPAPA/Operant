# OP-CAP-17D — Trust Risk Decision Engine

## 1. Stage objective

Provide a deterministic, explainable **Trust Risk Decision Engine** that combines the existing
Transaction Trust Intelligence signals into a single, auditable risk decision:

- OP-CAP-17A document trust signals (`DocumentTrustRun` / `DocumentTrustSignal`),
- OP-CAP-17B counterparty trust profile (`CounterpartyTrustProfile`),
- OP-CAP-17C payment obligation / outstanding balance state (`PaymentObligation`),
- explicit tenant policy defaults,
- forced-level rules,
- approval requirements,
- manual override with audit.

It outputs one of `LOW` / `MEDIUM` / `HIGH` / `CRITICAL` with a routing action, blocking and
human-review flags, the contributing reasons, and any approval requirement.

This is **not** a legal fraud verdict system. It never claims a document is fake. A HIGH/CRITICAL
outcome means *"high-risk signals detected; approval required before irreversible action."*

## 2. Entities added

| Entity | Table | Purpose |
|---|---|---|
| `TrustRiskDecision` | `trust_risk_decision` | The deterministic decision header (level, score, action, gate flags, status). |
| `TrustRiskSignalContribution` | `trust_risk_signal_contribution` | One normalized, explainable contribution per fired rule (never a JSON blob). |
| `TrustApprovalRequirement` | `trust_approval_requirement` | Records that a decision needs human approval before an irreversible action. |
| `TrustDecisionOverride` | `trust_decision_override` | Append-only audit of a manual override (before/after level + action + reason). |

Supporting enums: `TrustRiskAction`, `TrustRiskDecisionStatus`, `TrustRiskSignalSourceType`,
`TrustApprovalStatus`, `TrustRiskReasonCode`. The existing `TrustRiskLevel` (17A) is reused so the
four-stage scale is consistent across the whole trust subsystem.

## 3. Scoring model

Bounded, deterministic, cheap (no historical scans — only indexed by-id lookups):

1. `baseRisk = 0`.
2. Sum each contribution's `contributionScore`.
3. Subtract a small **trust discount** (max `10`) only when the counterparty is `TRUSTED` or has a
   trust score ≥ `80`.
4. Clamp to `0..100`.
5. Map score → level: `0..24 LOW`, `25..49 MEDIUM`, `50..74 HIGH`, `75..100 CRITICAL`.
6. `finalLevel = max(scoreLevel, maxForcedFloor)`.

The trust discount can **never** mask a forced HIGH/CRITICAL floor, because the floor is applied via
`max(...)` after the discount. Score arithmetic uses `int` (clamped); money comparisons use
`BigDecimal`; counterparty counters are `long`.

## 4. Forced-level rules

| Reason code | Forced floor |
|---|---|
| `DOCUMENT_CRITICAL_SIGNAL` (17A run = CRITICAL) | CRITICAL |
| `DOCUMENT_HIGH_RISK_SIGNAL` (17A run = HIGH) | HIGH |
| `BANK_ACCOUNT_HOLDER_MISMATCH` | HIGH |
| `DOCUMENT_DATE_FUTURE_FORCE_HIGH` | HIGH |
| `DUPLICATE_DOCUMENT_WITH_DIFFERENT_AMOUNT` (duplicate + total mismatch) | HIGH |
| `PAYMENT_AMOUNT_MISMATCH` (disputed, or amount > obligation total) | HIGH |
| `OUTSTANDING_BALANCE_HIGH` (remaining ≥ transaction amount) | HIGH |
| `PAYMENT_OVERDUE` (uses the 17C obligation risk level) | HIGH/CRITICAL when the obligation is HIGH/CRITICAL |
| `TENANT_POLICY_FORCED_APPROVAL` (high value + policy) | HIGH |
| `COUNTERPARTY_NEW_HIGH_VALUE_BANK_MISMATCH` (new/low-history + high value + bank mismatch) | CRITICAL |
| `CROSS_TENANT_REFERENCE` | CRITICAL (taxonomy reserved; see non-goals) |

Non-forcing contributions add weighted score only: `BANK_ACCOUNT_CHANGED_FROM_HISTORY`,
`COUNTERPARTY_LOW_TRUST`, `COUNTERPARTY_NEW_HIGH_VALUE`, `PAYMENT_PARTIAL_OPEN`,
`HIGH_VALUE_REQUIRES_APPROVAL`, `UNMATCHED_PAYMENT`.

## 5. Action policy

| Level | Action | humanReviewRequired | blocking |
|---|---|---|---|
| LOW | `CONTINUE` | false | false |
| MEDIUM | `CONTINUE_WITH_WARNING` | false | false |
| HIGH | `REQUIRE_APPROVAL` | true | true |
| CRITICAL | `BLOCK_AUTOMATION` | true | true |

`blocking` gates only irreversible commit/export/external-write/finalization. Draft/review work may
continue. The backend command service that performs the irreversible action is responsible for
checking that no `PENDING` approval requirement remains.

## 6. Manual override policy

`overrideDecision(tenantId, decisionId, newRiskLevel, newAction, reason, actor)`:

- Validates tenant ownership (`findByIdAndTenantId`).
- Requires a non-blank `reason` (rejected otherwise).
- A **CRITICAL decision can never be silently downgraded straight to LOW** (rejected at the service
  level). There is no granular security-role model yet, so this is a conservative guard — see
  *Limitations*.
- Records an append-only `TrustDecisionOverride` (previous + new level/action, reason, actor).
- Marks the decision `OVERRIDDEN` in place and applies the new effective level/action/flags.
- Original contributions are **never deleted**; a `MANUAL_OVERRIDE_APPLIED` evidence contribution is
  added.
- Cancels the pending approval requirement when the override drops below HIGH.
- Emits a `TRUST_RISK_DECISION_OVERRIDDEN` audit event and stores its id on the override row.

## 7. Approval requirement behavior

- HIGH → one `PENDING` requirement, `requiredAction = REQUIRE_APPROVAL`, `requiredPermissionCode = REVIEW_ACTION`.
- CRITICAL → one `PENDING` requirement, `requiredAction = ESCALATE`, `requiredPermissionCode = TRUST_RISK_OVERRIDE`.
- The `reasonCode` is the highest forced reason (or highest-severity contribution).
- Superseding a decision (re-evaluation of the same subject) cancels its pending requirements.

## 8. Tenant / security constraints

- Every table has a non-null `tenant_id`; every repository finder is tenant-scoped.
- API under `/api/v1/trust`:
  - `GET /api/v1/trust/risk-decisions/{id}` and `GET /api/v1/trust/risk-decisions` → `TRUST_READ`.
  - `POST /api/v1/trust/risk-decisions/evaluate` → `TRUST_RISK_EVALUATE`.
  - `POST /api/v1/trust/risk-decisions/{id}/override` → `TRUST_RISK_OVERRIDE` (stronger; evaluate is
    not sufficient).
- AI/bot/frontend/connector never mutate trusted state — all writes go through
  `TrustRiskDecisionService`.
- No raw document text, OCR text, prompt text, bank credentials, account numbers, IBAN, PAN/CVV, or
  secrets are stored or returned. All reason/explanation columns are bounded `VARCHAR(280)`.

## 9. Performance / index notes

- Evaluation uses only indexed by-id, tenant-scoped lookups (`findByIdAndTenantId`,
  `findByTenantIdAndCustomerAccountId`) — no full-table scans of signals/obligations.
- Indexes:
  - `trust_risk_decision (tenant_id, subject_type, subject_id, created_at DESC)`
  - `trust_risk_decision (tenant_id, risk_level, created_at DESC)`
  - `trust_risk_decision (tenant_id, status, created_at DESC)`
  - `trust_risk_signal_contribution (tenant_id, trust_risk_decision_id)`
  - `trust_approval_requirement (tenant_id, status, created_at DESC)`
  - `trust_approval_requirement (tenant_id, trust_risk_decision_id)`
  - `trust_decision_override (tenant_id, trust_risk_decision_id, overridden_at DESC)`
- List endpoints are paginated (`size` clamped to ≤ 100); contributions are normalized rows, not a
  JSON blob; reason summaries are bounded.

## 10. Non-goals

- No Stage 17E analytics / read-model dashboards.
- No Stage 17F AI memory/cache governance.
- No visual fraud detector and no legal fraud claim.
- No real bank/PSP integration, no payment credentials.
- No microservices/Kafka; the modular monolith is preserved.
- No rewrite of 17A/17B/17C.
- `CROSS_TENANT_REFERENCE` / `UNMATCHED_PAYMENT` codes are reserved in the taxonomy but not yet
  emitted (all lookups are tenant-scoped, so a cross-tenant reference cannot currently be reached).

## 11. Test coverage summary

Service (`TrustRiskDecisionServiceStage17DTest`, 13):
LOW continue, MEDIUM warning (no approval), HIGH require-approval (+blocking +requirement),
CRITICAL block-automation (+escalation requirement), forced HIGH beats high-trust discount, CRITICAL
not hidden by trust discount, overdue-HIGH obligation, manual override (evidence preserved + audit +
requirement cancelled), override without reason rejected, CRITICAL→LOW rejected, tenant isolation,
score clamp (≤100 and ≥0), idempotency key collapses duplicates.

Controller (`TrustRiskDecisionControllerStage17DTest`, 4):
GET returns contributions + approval requirements, list forwards tenant-scoped filters + paging,
evaluate compact response, override delegates to the service.

Permissions (`ApiPermissionInterceptorPermissionTest`, +5): GET=`TRUST_READ`,
evaluate=`TRUST_RISK_EVALUATE` (read not sufficient), override=`TRUST_RISK_OVERRIDE` (evaluate not
sufficient).

## Limitations

- Tenant policy defaults (high-value threshold = `10000`, force-approval-at-high-value = true) are
  static constants in `TrustRiskDecisionService`; a per-tenant policy table is future scope.
- No granular manager/security role model yet — the CRITICAL→LOW guard is a conservative service-level
  rejection. When a security-role model lands, the override path should gate downgrades on it.
- Evaluate idempotency is supported via the optional caller `idempotencyKey`; without a key, a new
  evaluation supersedes the prior active decision for the same subject.

## Next recommended stage

OP-CAP-17E Trust Analytics Read Models — only after 17D is green.
