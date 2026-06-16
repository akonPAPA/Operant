package com.orderpilot.domain.journey.events;

/**
 * OP-CAP-23 — status of one projector's checkpoint for one event. A COMPLETED/SKIPPED checkpoint makes
 * re-projection a no-op (idempotency guard).
 */
public enum JourneyProjectionCheckpointStatus {
  STARTED,
  COMPLETED,
  SKIPPED,
  FAILED
}
