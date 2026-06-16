# OP-CAP-26 — Missed-Event Recovery and Drain Monitoring

## Problem Solved

OP-CAP-25 added a bounded, tenant-safe Order Journey projection drain. OP-CAP-26 strengthens that runtime for
production operation: a drain pass can now recover missed work that was left pending, retry-ready failed, or
stuck in processing after a crash/restart, and operators can inspect a safe tenant-scoped health snapshot.

## Recovery Rules

- Recovery is tenant-scoped and bounded by the same clamped batch limit as the drain.
- `PENDING` events are processed oldest-first.
- `FAILED` events are retried only while under the retry cap and when their retry window has opened.
- stale `PROCESSING` events are re-queued through the existing runner when their checkpoint/start marker is
  older than the configured stale threshold.
- `PROCESSED`, `SKIPPED`, and `DEAD_LETTERED` events are never reprocessed.
- Existing checkpoint idempotency remains the guard against duplicate projection work.

## Monitoring Fields

The existing `GET /api/v1/order-journeys/projection-health` response now includes drain/recovery health:

- pending, processing, stale processing, failed, retryable failed, permanent failed, and dead-letter counts;
- oldest pending timestamp and age;
- last processed event id/time;
- last checkpoint event/status/update time;
- scheduler enabled and configured batch size;
- last drain start/completion/duration/status and safe error class;
- last recovery time and recovered count.

## Safety Constraints

- No frontend, AI, ERP, carrier, PSP, or external writes were added.
- No cross-tenant recovery is exposed.
- No raw payloads, stack traces, secrets, documents, customer messages, or payment/card data are returned.
- Queries are tenant-scoped and bounded; no unbounded recovery loop or all-event load is introduced.
- Scheduler configuration remains disabled by default and bounded when enabled.

## Verification

Targeted verification for this slice:

- `mvn -DskipTests compile`
- `mvn "-Dtest=OrderJourneyProjectionDrainServiceTest,OrderJourneyProjectionScheduledDrainConfigTest" test`

## Limitations

- Last drain/recovery status is process-local runtime state, not a durable audit table.
- There is no new operator UI; monitoring is backend/API only.
- Alerting thresholds are not implemented in this slice.
