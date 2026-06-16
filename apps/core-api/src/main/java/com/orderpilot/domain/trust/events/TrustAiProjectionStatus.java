package com.orderpilot.domain.trust.events;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime.
 *
 * Status of one projector's work on one event ({@link TrustAiProjectionCheckpoint}). {@code COMPLETED}
 * (or {@code SKIPPED}) is terminal and makes re-projection a no-op (idempotency guard). {@code FAILED}
 * may be retried.
 */
public enum TrustAiProjectionStatus {
  STARTED,
  COMPLETED,
  SKIPPED,
  FAILED
}
