# OP-CAP-17C — Payment Obligation Intelligence Foundation

## 1. Purpose

Give OrderPilot an internal, deterministic understanding of **what a counterparty owes, how much
they have paid, what remains, and whether their payment behaviour should affect trust** — without
becoming a payment processor, bank, or accounting engine.

Per tenant + counterparty (customer account), OrderPilot now knows:

- expected amount (`amountTotal`), paid (`amountPaid`), remaining (`amountRemaining`);
- whether an obligation is `OPEN`, `PARTIALLY_PAID`, `PAID`, `OVERDUE`, `DISPUTED`, `CANCELLED`, or
  `WRITTEN_OFF`;
- a deterministic `riskLevel` (reusing the 17A/17B `TrustRiskLevel` scale);
- how payment behaviour feeds the OP-CAP-17B Counterparty Trust Profile;
- a safe, read-only operator surface that never exposes raw bank/payment data.

## 2. Scope and non-scope

**In scope (this stage):** internal domain model (`PaymentObligation`, `PaymentAllocation`,
`PaymentObligationEvent`), a deterministic backend command/query service, append-only event history,
counterparty trust integration, bounded read-only APIs, audit, migration, tests.

**Out of scope:** real payment provider/PSP/bank integration; bank statement import; settlement
matching; invoice mirror creation; customer-facing payment UI; automatic order release; accounting
ledger; tax engine; multi-currency aggregation breakdown.

## 3. Why this is not a PSP / bank integration

- No external API call is made in code or tests.
- No Stripe/Adyen/bank/PSP/NFC/tap-to-pay handling, secrets, tokens, or provider credentials.
- No PAN, CVV, magnetic-stripe, raw NFC data, raw bank credentials, raw bank statement payloads,
  full IBAN, full routing/account numbers are stored or exposed.
- `PaymentAllocation` is a *safe internal/mirrored allocation* record (amount + currency + bounded
  internal ref) — **not** a bank transaction or PSP payload store.
- Webhook/PSP concepts do **not** update orders/invoices here; AI never marks anything paid.
  All mutation flows through the deterministic backend command service.

## 4. Domain model

| Entity | Table | Notes |
| --- | --- | --- |
| `PaymentObligation` | `payment_obligation` | Tenant-scoped receivable. `BigDecimal` NUMERIC(19,4) amounts; `risk_level` reuses `TrustRiskLevel`. Idempotency: unique `(tenant_id, source_type, source_ref_id)` when ref present. |
| `PaymentAllocation` | `payment_allocation` | Safe allocation (`APPLIED`/`REVERSED`); reversal is deterministic and audited; never deleted. |
| `PaymentObligationEvent` | `payment_obligation_event` | Append-only transition history (previous/new status + amounts, bounded `reason_summary`). |

Enums: `PaymentObligationStatus`, `PaymentObligationSourceType`, `PaymentAllocationSourceType`,
`PaymentAllocationStatus`, `PaymentObligationEventType`. Risk reuses `TrustRiskLevel`
(LOW/MEDIUM/HIGH/CRITICAL) for 1:1 alignment with counterparty trust signal severity.

## 5. Status logic (deterministic, clock-injected)

`CANCELLED` / `WRITTEN_OFF` are terminal — recalculation never auto-mutates them. `DISPUTED` is
preserved until explicitly resolved (amounts still update). Otherwise, given a deterministic `today`:

- `amountPaid >= amountTotal` → `PAID`
- `amountPaid == 0`, past due → `OVERDUE`; else → `OPEN`
- `0 < amountPaid < amountTotal`, past due → `OVERDUE`; else → `PARTIALLY_PAID`

**Overpayment is never silently accepted** — an allocation that would push paid above total is
rejected. Currency-mismatched allocations are rejected.

## 6. Risk logic

- `PAID` / `OPEN` / `CANCELLED` → `LOW`
- `PARTIALLY_PAID` → `MEDIUM`
- `DISPUTED` / `WRITTEN_OFF` → `HIGH`
- `OVERDUE`: 1–7 days late → `MEDIUM`; > 7 days → `HIGH` (conservative; no finance policy invented —
  `CRITICAL` by amount/age threshold is deferred).

Risk is computed deterministically; **AI is never consulted**.

## 7. Money / sensitive-data policy

- All amounts are `BigDecimal` normalized to scale 4 (HALF_UP); never floating point.
- Business counters use `long` (on the 17B profile).
- Currency is validated to a 3-letter ISO-like uppercase code.
- No raw bank/card/NFC/statement data is stored or returned; `externalReference` (≤120) and
  `obligationNumber` (≤80) are bounded safe identifiers only. Reason fields are bounded (≤280).

## 8. OP-CAP-17B Counterparty Trust Profile integration

In the **same transaction** as the obligation mutation (commit/roll back together), via two narrow
additive methods on `CounterpartyTrustProfileService`:

- `applyPaymentObligationSignal(...)` — records a `PAYMENT_SIGNAL`-sourced counterparty signal,
  optionally bumps `overduePaymentCount` / `disputedCount`, recomputes the deterministic score,
  appends an evidence snapshot, and audits.
- `recordPaymentReliabilityUpdate(...)` — sets `lastPaymentAt` and recomputes payment reliability.

Triggers: transition into `OVERDUE` → `PAYMENT_OVERDUE` (HIGH, increments `overduePaymentCount`);
`markDisputed` → `DISPUTE_HISTORY_HIGH` (HIGH, increments `disputedCount`); transition into
`PARTIALLY_PAID` → `PARTIAL_PAYMENT_OPEN` (MEDIUM); any allocation → `lastPaymentAt` refresh.

The 17B critical/high tier **floor is respected** — a HIGH payment signal floors the tier to at least
`WATCHLIST` regardless of positive history, and document trust signals are never erased. If there is
no `customerAccountId` no global/unscoped profile is created.

## 9. API endpoints (read-only, `TRUST_READ`)

All under the existing `/api/v1/trust` prefix (auto-guarded by `TRUST_READ`):

- `GET /api/v1/trust/counterparties/{customerAccountId}/payment-summary`
- `GET /api/v1/trust/counterparties/{customerAccountId}/payment-obligations?status=&limit=25`
- `GET /api/v1/trust/payment-obligations/{obligationId}`

DTOs (`PaymentObligationDtos`): `PaymentObligationResponse` (+ bounded `recentEvents`),
`PaymentObligationEventResponse`, `CustomerPaymentSummaryResponse`. Entities are never returned.
No mutation API is exposed — payment state changes only through the backend command service.

## 10. Tenant isolation

Every table carries `tenant_id`; every repository query, service path, DTO, and API response is
tenant-scoped (tenant resolved from `TenantContext`, never trusted from the path). Tests prove Tenant
A obligations/summaries are invisible to Tenant B and never update Tenant B's trust profile.

## 11. Auditing

`AuditEvent` (bounded metadata: obligationId, customerAccountId, status, riskLevel, amountRemaining,
currency — no raw refs) is written for create, allocate, reverse, dispute, resolve, cancel, write-off,
and overdue detection. Trust updates additionally emit the existing 17B audit actions.

## 12. Performance / indexing

- No unbounded scans; all reads supply a clamped `Pageable` (default 25 / max 100).
- Summary uses a bounded per-status aggregate (≤7 rows) + distinct-currency query — no per-row scan.
- Overdue detection is bounded (≤200 candidates/request) and uses an index-friendly query.
- Indexes: `(tenant_id, customer_account_id, status)`, `(tenant_id, customer_account_id, due_date)`,
  `(tenant_id, risk_level, updated_at DESC)`, partial unique `(tenant_id, source_type, source_ref_id)`;
  allocation `(tenant_id, payment_obligation_id)`, `(tenant_id, customer_account_id, allocated_at DESC)`;
  event `(tenant_id, payment_obligation_id, created_at DESC)`, `(tenant_id, customer_account_id,
  created_at DESC)`, `(tenant_id, event_type, created_at DESC)`.
- Overdue detection uses an injected `Clock` for determinism; status updates are transactional.

## 13. Tests added

- `PaymentObligationServiceStage17CTest` (24): create/open/overdue/partial/paid; overpayment &
  currency-mismatch rejection; reversal; disputed preservation; cancelled/written-off not auto-mutated;
  append-only events; BigDecimal scale; idempotency; 17B integration (overdue count, lastPaymentAt,
  reliability, signals, tenant isolation, tier floor); summary aggregate + mixed-currency withholding;
  tenant-scoped bounded reads; limit clamp.
- `PaymentObligationControllerStage17CTest` (5): authorized reads; no sensitive fields
  (iban/pan/cvv/routingNumber/accountNumber/bankCredential/cardNumber/nfcPayload); 404; raw
  limit/status forwarding.

Regression: 17A (17), 17B (21), `ApiPermissionInterceptorPermissionTest` (64) all green.

## 14. Known limitations

- No real payment provider/PSP/bank integration; no bank statement import; no settlement matching.
- No invoice/order mirror creation (only mirror *reference* fields exist).
- No customer-facing portal payment UI; no automatic order release; no accounting ledger; no tax engine.
- Multi-currency summaries withhold amounts (`currency = "MIXED"`, amounts null); per-currency
  breakdown is deferred. Counts remain valid.
- Overdue `CRITICAL` severity by amount/age threshold is deferred (conservative HIGH).
- Frontend deferred (see below).

## 15. Next stage recommendation

- **Frontend (deferred):** add a read-only payment summary panel to the counterparty trust profile /
  validation review surface once counterparty binding is reliable.
- **OP-CAP-17D:** order-pattern / behavioural trust signals, then a controlled feed from invoice/order
  mirrors into `PaymentObligation` (still backend-deterministic, still no external writes).
