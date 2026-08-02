# ADR-011 — High-load workload isolation and backpressure

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** Heavy work (OCR, AI, connector sync, delivery, analytics, reconciliation) must not block the
  fast request path, and one tenant must not starve others.
- **Current evidence:** `ProcessingJob` (PENDING work items), `WorkerJobLeaseService` (lease/fencing),
  `RedisRateCounter` (rate limiting), `RuntimeRateRedisConfiguration` (runtime admission).
- **Options:** (a) inline heavy work; (b) fast-ack + async worker pools with tenant fairness + backpressure.
- **Decision:** (b). Fast path = verify → tenant resolution → bounds → idempotency → minimal durable
  persistence → ack. Heavy work in per-type job tables/queues, per-workload pools, per-tenant concurrency,
  weighted fairness, priority classes, global ceilings, provider limits, large-file isolation, backpressure,
  degraded mode, bounded retry + DLQ/reconciliation. Ordering keys use only the invariant's need
  (tenant+quoteId, tenant+orderId, tenant+paymentObligationId, tenant+connectionId, tenant+inquiryId).
- **Consequences:** predictable latency; fair multi-tenant throughput; graceful degradation.
- **Rejected alternatives:** inline heavy work (latency spikes, head-of-line blocking).
- **Migration impact:** extend existing ProcessingJob into typed queues + fairness (incremental).
- **Security impact:** positive — resource-exhaustion resistance.
- **Performance impact:** primary positive.
- **Reversibility:** high.
- **NOT_PROVEN:** per-tenant fairness/priority/degraded-mode not yet implemented; load figures unmeasured.
