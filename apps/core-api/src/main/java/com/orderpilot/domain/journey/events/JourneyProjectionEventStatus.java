package com.orderpilot.domain.journey.events;

/**
 * OP-CAP-23 — lifecycle status of a durable Order Journey projection event. Mirrors the OP-CAP-18 trust
 * event runtime: PENDING/PROCESSING are non-terminal; PROCESSED/SKIPPED/DEAD_LETTERED are terminal; FAILED
 * is retried (with backoff) until the retry cap, then DEAD_LETTERED. No infinite retry.
 */
public enum JourneyProjectionEventStatus {
  PENDING,
  PROCESSING,
  PROCESSED,
  SKIPPED,
  FAILED,
  DEAD_LETTERED
}
