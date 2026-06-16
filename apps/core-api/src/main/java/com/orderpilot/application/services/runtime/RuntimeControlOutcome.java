package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-27 Runtime Control Mainline — the single, stable outcome of a runtime-control decision for an
 * AI/workload-like operation. It is a safe token only (no payload, no tenant internals) and is suitable
 * for metrics and audit.
 *
 * <ul>
 *   <li>{@code ALLOW_SYNC} — proceed now on the request thread (deterministic rules path or a small
 *       synchronous AI call).
 *   <li>{@code ALLOW_ASYNC} — allowed, but the work must be handed to the async runtime, not run inline.
 *   <li>{@code DENY_ENTITLEMENT} — the tenant is not entitled to the feature (403).
 *   <li>{@code DENY_QUOTA} — the tenant's quota for the metric is exhausted (403).
 *   <li>{@code DENY_RATE_LIMIT} — the per-window rate budget is exhausted (429).
 *   <li>{@code DEDUPED} — a duplicate idempotent request; the existing result should be returned and no
 *       provider/work is invoked.
 *   <li>{@code NEEDS_REVIEW} — the workload (suspicious prompt-injection signal or bulk) must be routed
 *       to a human operator rather than auto-processed.
 *   <li>{@code UNSUPPORTED} — the workload could not be classified and fails safe to manual handling.
 * </ul>
 */
public enum RuntimeControlOutcome {
  ALLOW_SYNC,
  ALLOW_ASYNC,
  DENY_ENTITLEMENT,
  DENY_QUOTA,
  DENY_RATE_LIMIT,
  DEDUPED,
  NEEDS_REVIEW,
  UNSUPPORTED;

  /** Whether the operation may proceed (synchronously or asynchronously). */
  public boolean allowed() {
    return this == ALLOW_SYNC || this == ALLOW_ASYNC;
  }
}
