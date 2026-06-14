# OP-CAP-17E — Trust Analytics Read Models

## 1. Objective

Turn the existing OP-CAP-17A (document trust), 17B (counterparty trust), 17C (payment obligation /
outstanding balance), and 17D (trust risk decision) **operational write models** into fast, tenant-scoped,
explainable **read models** for cockpit and analytics queries.

This is a **CQRS-lite projection layer** — not a new scoring engine and not a replacement for 17D. It
derives precomputed, rebuildable rows so dashboards do not run heavy joins over the operational tables on
every request.

**Core principle:** operational write models remain the single source of truth. Read models are derived,
rebuildable, and tenant-scoped. Projection never mutates any 17A–17D record and never invents values —
every figure is read from a persisted source row.

## 2. Read model list

| Read model | Table | Purpose | Natural key |
|---|---|---|---|
| `TrustReviewQueueView` | `trust_review_queue_view` | Operator queue for HIGH/CRITICAL / blocking / pending-approval decisions | `(tenant_id, trust_risk_decision_id)` |
| `CounterpartyTrustDashboardView` | `counterparty_trust_dashboard_view` | Per-counterparty trust profile summary | `(tenant_id, counterparty_id)` |
| `OutstandingDebtView` | `outstanding_debt_view` | Unpaid / partial / overdue / disputed / written-off exposure | `(tenant_id, payment_obligation_id)` |
| `DocumentAnomalyTrendView` | `document_anomaly_trend_view` | Document trust anomaly counts per period/signal/severity | `(tenant_id, period_key, signal_code, severity, counterparty_id)` |
| `TrustRiskDistributionView` | `trust_risk_distribution_view` | Risk decision distribution per period | `(tenant_id, period_key)` |

Migration: **`V48__trust_analytics_read_models.sql`** (next after V47; no applied migration was edited).

## 3. Projection strategy

`TrustAnalyticsProjectionService` exposes synchronous, explicit, tenant-scoped rebuild methods:

1. `rebuildTrustReviewQueueForDecision(tenantId, trustRiskDecisionId)` — upserts the queue row when the
   decision is queue-worthy (risk ≥ HIGH, blocking, or human-review-required, and not SUPERSEDED/CANCELLED);
   removes the stale row otherwise. Derives `approvalStatus` (latest approval requirement) and
   `topReasonCode` (forced-floor contribution, else highest-severity contribution).
2. `rebuildCounterpartyTrustDashboard(tenantId, counterpartyId)` — combines the 17B profile, 17C per-status
   obligation aggregates, and 17D HIGH/CRITICAL decision counts/last-seen timestamps. Removes the row when
   the counterparty has no trust activity at all.
3. `rebuildOutstandingDebtViewForObligation(tenantId, paymentObligationId)` — upserts while the obligation
   carries exposure (any status except `PAID`/`CANCELLED`); removes otherwise. Derives `daysOverdue`,
   `orderId`/`invoiceMirrorId` from the obligation source, and the linked active 17D decision + reason.
4. `rebuildDocumentAnomalyTrends(tenantId, periodKey)` — bounded group-by over 17A signals in the period,
   **delete-then-insert per period** so a rebuild is idempotent.
5. `rebuildTrustRiskDistribution(tenantId, periodKey)` — single conditional-aggregate row per period
   (upsert).
6. `rebuildAllForTenant(tenantId)` — bounded best-effort batch (see §8).

**Idempotency:** every rebuildable view has a unique natural key. Upsert finds-then-updates; trend rows use
delete-then-insert. Running any rebuild twice produces the same rows — no appended duplicates (test:
`rebuildIsIdempotentAndDoesNotDuplicate`).

**Future outbox/projector hook (documented, not built this stage):** when an outbox/projector runtime
exists, 17D decision-created/overridden, 17C obligation transitions, and 17A run/signal completion would
call these same methods asynchronously. In this stage they are synchronous and directly tested.

## 4. Source-of-truth vs read model rule

- **Write models (17A–17D) are authoritative.** Projection only reads them.
- Read models are **derived and disposable** — they can be dropped and fully rebuilt from the operational
  tables at any time.
- Projection never writes to 17A–17D tables and never calls the 17D scoring engine; it reads already-computed
  decisions, levels, scores, and reason codes.

## 5. Endpoint list

All under `/api/v1/trust/analytics` (controller `TrustAnalyticsController`):

| Method | Path | Permission |
|---|---|---|
| GET | `/review-queue` | `TRUST_ANALYTICS_READ` |
| GET | `/counterparties/{counterpartyId}` | `TRUST_ANALYTICS_READ` |
| GET | `/outstanding-debt` | `TRUST_ANALYTICS_READ` |
| GET | `/document-anomalies` | `TRUST_ANALYTICS_READ` |
| GET | `/risk-distribution` | `TRUST_ANALYTICS_READ` |
| POST | `/rebuild?tenantOnly=true` | `TRUST_ANALYTICS_REBUILD` |

Read filters/paging: review queue (`riskLevel`, `approvalStatus`, `blocking`, `page`, `size`); outstanding
debt (`status`, `riskLevel`, `counterpartyId`, `page`, `size`); document anomalies (`fromPeriod`,
`toPeriod`, `signalCode`, `severity`, `limit`); risk distribution (`periodKey` or `fromPeriod`/`toPeriod`,
`limit`). All list endpoints are bounded and limit-clamped.

## 6. Indexes / performance notes

- Reads hit precomputed rows; no per-request join over operational tables.
- Indexes added per view (see V48): review queue on `(tenant, risk_level, created_at)`,
  `(tenant, approval_status, created_at)`, `(tenant, blocking, created_at)`, `(tenant, subject)`; dashboard
  on `(tenant, trust_tier)`, `(tenant, trust_score)`, `(tenant, outstanding_amount)`,
  `(tenant, last_high_risk_at)`; debt on `(tenant, status, due_date)`, `(tenant, risk_level, amount_remaining)`,
  `(tenant, counterparty_id, status)`, `(tenant, amount_remaining)`; trend on `(tenant, period_key)`,
  `(tenant, signal_code, period_key)`, `(tenant, severity, period_key)`; distribution on `(tenant, period_key)`.
- Projection aggregates are bounded: the anomaly group-by yields at most one row per (signal, severity) per
  period; the distribution aggregate is a single conditional-aggregate query (one scan per rebuild).
- `rebuildAllForTenant` is bounded by `REBUILD_DECISION_SCAN_LIMIT` (200) and
  `REBUILD_OBLIGATION_SCAN_LIMIT` (200) — never an unbounded full-tenant scan.
- **Period convention:** daily period key `yyyy-MM-dd` in UTC; `period_start`/`period_end` is the half-open
  `[day, day+1)` window. (No monthly/weekly rollup existed to follow; daily is sufficient for 17E.)
- **Money:** `BigDecimal` NUMERIC(19,4); currency preserved per debt row. Dashboard `outstandingAmount` is
  populated only for a single-currency counterparty; mixed currencies set `primaryCurrency = "MIXED"` and a
  null amount. **No FX conversion is ever performed.**

## 7. Tenant isolation rules

- Every read-model row carries `tenant_id` (NOT NULL, FK to `tenant`).
- Every projection lookup and every query finder is tenant-scoped; projecting under tenant B with tenant A's
  ids yields nothing (test: `rebuildAndReadNeverCrossTenantBoundaries`).
- Query services resolve the tenant from `TenantContext`; path/query ids are never trusted across tenants.
- No cross-tenant / global trust analytics (non-goal).

## 8. Limitations

- **No data safety/PII regression:** read models store only bounded, business-safe fields — no raw
  document/OCR/prompt text, bank credentials, IBAN/account numbers, PAN/CVV, card/NFC payloads, audit
  payloads, or per-line margin/cost.
- `DocumentAnomalyTrendView.counterpartyId` is **null** in this stage: 17A signals are keyed by trust run
  (which references a source document), not a counterparty, so no reliable counterparty association exists
  yet. `riskLevel` on trend rows is null (signals carry severity, not a risk level). The unique key keeps
  the counterparty column so a future enrichment can populate it without a schema change.
- `CounterpartyTrustDashboardView.orderCount` currently equals `completedOrderCount` — 17B tracks only a
  completed-order counter, not a separate total-order count.
- `paidOnTimeCount` is approximated by the count of `PAID` obligations; due-date-vs-payment-date "on time"
  nuance is not stored in 17C.
- `overdueCount` is the 17B lifetime overdue-behavior counter; `overduePaymentObligationCount` is the
  current count of `OVERDUE` obligations (the two are intentionally distinct).
- `rebuildAllForTenant` is bounded best-effort (today's period aggregates + most-recent-N active decisions
  and their counterparties/obligations). Full historical backfill across all decisions/obligations is out of
  scope; targeted rebuilds cover individual records precisely.
- No projector/outbox runtime yet — rebuilds are synchronous and caller-driven (hook documented in §3).

## 9. Test coverage summary

- **Service/projection** (`TrustAnalyticsProjectionServiceStage17ETest`, 12): high-risk → queue;
  critical-blocking action; override updates queue + distribution override count; dashboard combines
  profile + obligations + decision counts; outstanding debt amounts/status/risk/days-overdue; PAID
  obligation removed from debt; anomaly trend grouping by code/severity; risk distribution by level;
  idempotent rebuild (no duplicates); tenant isolation; pagination + filtering; bounded `rebuildAllForTenant`.
- **Controller** (`TrustAnalyticsControllerStage17ETest`, 6): each read endpoint returns the bounded DTO and
  forwards tenant-scoped filters/paging; rebuild delegates to the projection service.
- **Permissions** (`ApiPermissionInterceptorPermissionTest`, +5): analytics reads require
  `TRUST_ANALYTICS_READ` (generic `TRUST_READ` is insufficient); rebuild requires `TRUST_ANALYTICS_REBUILD`
  (read is insufficient).
- **Regression:** 17A/17B/17C/17D service + controller suites and the full permission interceptor suite stay
  green.

## 10. Next stage recommendation

**OP-CAP-17F — AI Data Runtime / Tenant-Scoped AI Memory Governance.** (Out of scope here; not started.)
