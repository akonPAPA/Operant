package com.orderpilot.domain.intake;

public enum ProcessingJobStatus {
  PENDING,
  PROCESSING,
  SUCCEEDED,
  FAILED,
  NEEDS_REVIEW,
  // OP-CAP-07D: terminal status for an AI-worker result the worker itself rejected (malformed/unsafe
  // input per worker-local checks). Stored as a String column, so no migration is required.
  REJECTED
}
